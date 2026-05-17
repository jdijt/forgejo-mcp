package eu.derfniw.mcp.forgejo.broker.endpoint;

import eu.derfniw.mcp.forgejo.broker.crypto.TokenCrypto;
import eu.derfniw.mcp.forgejo.broker.model.TokenCryptoException;
import eu.derfniw.mcp.forgejo.broker.crypto.TokenType;
import eu.derfniw.mcp.forgejo.broker.model.AuthCodeEntry;
import eu.derfniw.mcp.forgejo.broker.model.AuthServerMetadata;
import eu.derfniw.mcp.forgejo.broker.model.BadRequest;
import eu.derfniw.mcp.forgejo.broker.model.CimdDocument;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoTokens;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoUser;
import eu.derfniw.mcp.forgejo.broker.model.OAuthError;
import eu.derfniw.mcp.forgejo.broker.model.PendingAuth;
import eu.derfniw.mcp.forgejo.broker.model.ProtectedResourceMetadata;
import eu.derfniw.mcp.forgejo.broker.model.UpstreamFailure;
import eu.derfniw.mcp.forgejo.broker.service.CimdResolver;
import eu.derfniw.mcp.forgejo.broker.service.BrokerUris;
import eu.derfniw.mcp.forgejo.broker.service.ForgejoOAuthClient;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import eu.derfniw.mcp.forgejo.config.ForgejoConfig;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The broker's downstream OAuth surface in a single resource:
 * discovery metadata ({@code /.well-known/*}), the authorization endpoint, and the Forgejo callback.
 * Error paths throw {@link eu.derfniw.mcp.forgejo.broker.model.BrokerException} subtypes — see
 * {@link BrokerExceptionMapper}. Services throw domain exceptions directly; this resource only
 * translates technical exceptions ({@link TokenCryptoException}, {@link IllegalArgumentException})
 * or attaches request-specific context to upstream failures ({@link UpstreamFailure} →
 * {@link OAuthError} in the callback, where the client's {@code redirect_uri} is known).
 */
@Path("/")
public class OAuthResource {

    @Inject
    BrokerConfig broker;

    @Inject
    ForgejoConfig forgejo;

    @Inject
    BrokerUris brokerUris;

    @Inject
    CimdResolver cimdResolver;

    @Inject
    TokenCrypto tokenCrypto;

    @Inject
    ForgejoOAuthClient forgejoOAuth;

    // ---------------------------------------------------------------------
    // Discovery metadata
    // ---------------------------------------------------------------------

    @GET
    @Path("/.well-known/oauth-authorization-server")
    @Produces(MediaType.APPLICATION_JSON)
    public AuthServerMetadata authServerMetadata() {
        return new AuthServerMetadata(
                brokerUris.issuer().toString(),
                brokerUris.authorizeUri().toString(),
                brokerUris.tokenUri().toString(),
                List.of("code"),
                List.of("authorization_code", "refresh_token"),
                List.of("S256"),
                List.of("none"),
                forgejo.oauth().scopes(),
                true);
    }

    @GET
    @Path("/.well-known/oauth-protected-resource/mcp")
    @Produces(MediaType.APPLICATION_JSON)
    public ProtectedResourceMetadata protectedResourceMetadata() {
        return new ProtectedResourceMetadata(
                brokerUris.mcpResourceUri().toString(),
                List.of(brokerUris.issuer().toString()),
                List.of("header"),
                forgejo.oauth().scopes());
    }

    // ---------------------------------------------------------------------
    // Authorization endpoint — downstream-facing
    // ---------------------------------------------------------------------

    @GET
    @Path("/authorize")
    public Response authorize(
            @QueryParam("response_type") @Nullable String responseType,
            @QueryParam("client_id") @Nullable String clientId,
            @QueryParam("redirect_uri") @Nullable String redirectUri,
            @QueryParam("scope") @Nullable String scope,
            @QueryParam("state") @Nullable String state,
            @QueryParam("code_challenge") @Nullable String codeChallenge,
            @QueryParam("code_challenge_method") @Nullable String codeChallengeMethod) {

        if (clientId == null || clientId.isBlank()) {
            throw new BadRequest("missing client_id");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new BadRequest("missing redirect_uri");
        }

        CimdDocument cimd = cimdResolver.resolve(clientId);

        URI redirect;
        try {
            redirect = URI.create(redirectUri);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("redirect_uri is not a valid URI", e);
        }
        if (!cimd.redirectUris().contains(redirect)) {
            throw new BadRequest("redirect_uri not registered in client metadata");
        }

        if (!"code".equals(responseType)) {
            throw new OAuthError(redirect, state, "unsupported_response_type", "response_type must be 'code'");
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new OAuthError(redirect, state, "invalid_request", "code_challenge is required");
        }
        // Intellij marks this null check as unneeded,
        // but unfortunately it is needed to satisfy NullAway :).
        if (codeChallengeMethod == null || !"S256".equals(codeChallengeMethod)) {
            throw new OAuthError(redirect, state, "invalid_request", "code_challenge_method must be S256");
        }

        List<String> requestedScopes = parseScopes(scope);
        List<String> advertised = forgejo.oauth().scopes();
        if (!requestedScopes.isEmpty() && !new HashSet<>(advertised).containsAll(requestedScopes)) {
            throw new OAuthError(redirect, state, "invalid_scope", "requested scope exceeds advertised set");
        }
        List<String> effectiveScopes = requestedScopes.isEmpty() ? advertised : requestedScopes;

        PendingAuth pending = new PendingAuth(
                clientId,
                redirect,
                state == null ? "" : state,
                codeChallenge,
                codeChallengeMethod,
                effectiveScopes,
                Instant.now().plus(broker.pendingAuthTtl()));
        String stateToken = tokenCrypto.encode(TokenType.PENDING_AUTH, pending);

        URI forgejoAuthorize = UriBuilder.fromUri(forgejo.baseUrl())
                .path("login")
                .path("oauth")
                .path("authorize")
                .queryParam("client_id", forgejo.oauth().clientId())
                .queryParam("redirect_uri", brokerUris.callbackUri())
                .queryParam("response_type", "code")
                .queryParam("state", stateToken)
                .queryParam("scope", String.join(" ", effectiveScopes))
                .build();
        Log.debugf("Bouncing user to Forgejo authorize: %s", forgejoAuthorize);
        return Response.status(Response.Status.FOUND).location(forgejoAuthorize).build();
    }

    // ---------------------------------------------------------------------
    // Forgejo callback — upstream-facing
    // ---------------------------------------------------------------------

    @GET
    @Path("/oauth/callback")
    public Response oauthCallback(
            @QueryParam("code") @Nullable String code,
            @QueryParam("state") @Nullable String state,
            @QueryParam("error") @Nullable String error,
            @QueryParam("error_description") @Nullable String errorDescription) {

        if (state == null || state.isBlank()) {
            throw new BadRequest("missing state");
        }
        PendingAuth pending = tokenCrypto.decode(TokenType.PENDING_AUTH, state, PendingAuth.class);

        if (error != null && !error.isBlank()) {
            throw new OAuthError(
                    pending.redirectUri(), pending.mcpState(), error, errorDescription == null ? "" : errorDescription);
        }

        if (code == null || code.isBlank()) {
            throw new OAuthError(pending.redirectUri(), pending.mcpState(), "invalid_request", "missing code");
        }

        ForgejoTokens tokens;
        ForgejoUser user;
        try {
            tokens = forgejoOAuth.exchangeCode(code, brokerUris.callbackUri().toString());
            user = forgejoOAuth.fetchUser(tokens.accessToken());
        } catch (UpstreamFailure e) {
            // Forgejo failed; we have enough context to tell the client via the OAuth redirect.
            throw new OAuthError(
                    pending.redirectUri(), pending.mcpState(), "server_error", "upstream exchange failed");
        }

        AuthCodeEntry authCode = new AuthCodeEntry(
                pending.mcpClientId(),
                pending.redirectUri(),
                pending.codeChallenge(),
                pending.codeChallengeMethod(),
                pending.scope(),
                tokens,
                user,
                Instant.now().plus(broker.authCodeTtl()));
        String brokerCode = tokenCrypto.encode(TokenType.AUTH_CODE, authCode);

        UriBuilder target = UriBuilder.fromUri(pending.redirectUri()).queryParam("code", brokerCode);
        if (!pending.mcpState().isBlank()) {
            target.queryParam("state", pending.mcpState());
        }
        return Response.status(Response.Status.FOUND).location(target.build()).build();
    }

    private static List<String> parseScopes(@Nullable String scope) {
        if (scope == null || scope.isBlank()) return List.of();
        return Arrays.stream(scope.trim().split("\\s+")).distinct().toList();
    }
}
