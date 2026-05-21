package eu.derfniw.mcp.forgejo.broker.endpoint;

import eu.derfniw.mcp.forgejo.broker.crypto.TokenCrypto;
import eu.derfniw.mcp.forgejo.broker.crypto.TokenType;
import eu.derfniw.mcp.forgejo.broker.model.AccessTokenEntry;
import eu.derfniw.mcp.forgejo.broker.model.AuthCodeEntry;
import eu.derfniw.mcp.forgejo.broker.model.AuthServerMetadata;
import eu.derfniw.mcp.forgejo.broker.model.BadRequest;
import eu.derfniw.mcp.forgejo.broker.model.CimdDocument;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoTokens;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoUser;
import eu.derfniw.mcp.forgejo.broker.model.OAuthError;
import eu.derfniw.mcp.forgejo.broker.model.PendingAuth;
import eu.derfniw.mcp.forgejo.broker.model.ProtectedResourceMetadata;
import eu.derfniw.mcp.forgejo.broker.model.RefreshTokenEntry;
import eu.derfniw.mcp.forgejo.broker.model.TokenCryptoException;
import eu.derfniw.mcp.forgejo.broker.model.TokenError;
import eu.derfniw.mcp.forgejo.broker.model.TokenResponse;
import eu.derfniw.mcp.forgejo.broker.model.UpstreamFailure;
import eu.derfniw.mcp.forgejo.broker.service.BrokerUris;
import eu.derfniw.mcp.forgejo.broker.service.CimdResolver;
import eu.derfniw.mcp.forgejo.broker.service.ForgejoOAuthClient;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import eu.derfniw.mcp.forgejo.config.ForgejoConfig;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private static List<String> parseScopes(@Nullable String scope) {
        if (scope == null || scope.isBlank()) return List.of();
        return Arrays.stream(scope.trim().split("\\s+")).distinct().toList();
    }

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

    // ---------------------------------------------------------------------
    // Authorization endpoint — downstream-facing
    // ---------------------------------------------------------------------

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
    // Forgejo callback — upstream-facing
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

        // The broker needs read:user upstream to resolve the Forgejo identity for SecurityIdentity,
        // regardless of which scopes the downstream client asked for. The downstream effectiveScopes
        // (returned to the client and stored as roles) still reflect only what was requested.
        Set<String> upstreamScopes = new LinkedHashSet<>(effectiveScopes);
        upstreamScopes.add("read:user");

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
                .queryParam("scope", String.join(" ", upstreamScopes))
                .build();
        Log.debugf("Bouncing user to Forgejo authorize: %s", forgejoAuthorize);
        return Response.status(Response.Status.FOUND).location(forgejoAuthorize).build();
    }

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
            // Log the underlying failure so operators can diagnose — the redirect alone is opaque.
            Log.warnf(e, "Forgejo upstream failed during /oauth/callback; rendering server_error to client");
            throw new OAuthError(pending.redirectUri(), pending.mcpState(), "server_error", "upstream exchange failed");
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

    // ---------------------------------------------------------------------
    // Token endpoint — downstream-facing
    // ---------------------------------------------------------------------

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public TokenResponse token(
            @FormParam("grant_type") @Nullable String grantType,
            @FormParam("code") @Nullable String code,
            @FormParam("redirect_uri") @Nullable String redirectUri,
            @FormParam("client_id") @Nullable String clientId,
            @FormParam("code_verifier") @Nullable String codeVerifier,
            @FormParam("refresh_token") @Nullable String refreshToken) {

        if (grantType == null || grantType.isBlank()) {
            throw new TokenError("invalid_request", "missing grant_type");
        }
        return switch (grantType) {
            case "authorization_code" -> authorizationCodeGrant(code, redirectUri, clientId, codeVerifier);
            case "refresh_token" -> refreshTokenGrant(refreshToken, clientId);
            default -> throw new TokenError("unsupported_grant_type", "grant_type '" + grantType + "' not supported");
        };
    }

    private TokenResponse authorizationCodeGrant(
            @Nullable String code,
            @Nullable String redirectUri,
            @Nullable String clientId,
            @Nullable String codeVerifier) {
        if (code == null || code.isBlank()) {
            throw new TokenError("invalid_request", "missing code");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new TokenError("invalid_request", "missing redirect_uri");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new TokenError("invalid_request", "missing client_id");
        }
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new TokenError("invalid_request", "missing code_verifier");
        }

        AuthCodeEntry entry;
        try {
            entry = tokenCrypto.decode(TokenType.AUTH_CODE, code, AuthCodeEntry.class);
        } catch (TokenCryptoException e) {
            throw new TokenError("invalid_grant", "code is invalid or expired", e);
        }

        if (!entry.mcpClientId().equals(clientId)) {
            throw new TokenError("invalid_grant", "client_id does not match the issued code");
        }
        if (!entry.redirectUri().toString().equals(redirectUri)) {
            throw new TokenError("invalid_grant", "redirect_uri does not match the issued code");
        }
        if (!verifyPkceS256(codeVerifier, entry.codeChallenge())) {
            throw new TokenError("invalid_grant", "PKCE verification failed");
        }

        return mintTokens(entry.mcpClientId(), entry.scope(), entry.forgejoTokens(), entry.forgejoUser());
    }

    private TokenResponse refreshTokenGrant(@Nullable String refreshToken, @Nullable String clientId) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new TokenError("invalid_request", "missing refresh_token");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new TokenError("invalid_request", "missing client_id");
        }

        RefreshTokenEntry entry;
        try {
            entry = tokenCrypto.decode(TokenType.REFRESH_TOKEN, refreshToken, RefreshTokenEntry.class);
        } catch (TokenCryptoException e) {
            throw new TokenError("invalid_grant", "refresh_token is invalid or expired", e);
        }

        if (!entry.mcpClientId().equals(clientId)) {
            throw new TokenError("invalid_grant", "client_id does not match the issued refresh_token");
        }

        ForgejoTokens fresh;
        try {
            fresh = forgejoOAuth.refresh(entry.forgejoTokens().refreshToken());
        } catch (UpstreamFailure e) {
            // RFC 6749 §5.2: surface upstream refresh failure as invalid_grant so the client re-authorizes.
            throw new TokenError("invalid_grant", "upstream refresh failed", e);
        }

        return mintTokens(entry.mcpClientId(), entry.scope(), fresh, entry.forgejoUser());
    }

    private TokenResponse mintTokens(
            String mcpClientId, List<String> scope, ForgejoTokens forgejoTokens, ForgejoUser forgejoUser) {
        Instant now = Instant.now();
        // Broker AT TTL is capped by the upstream Forgejo AT lifetime so a single decode covers the call.
        Instant atExpires = earliest(now.plus(broker.accessTokenTtl()), forgejoTokens.accessExpiresAt());
        Instant rtExpires = now.plus(broker.refreshTokenTtl());

        AccessTokenEntry at = new AccessTokenEntry(mcpClientId, scope, forgejoTokens, forgejoUser, atExpires);
        RefreshTokenEntry rt = new RefreshTokenEntry(mcpClientId, scope, forgejoTokens, forgejoUser, rtExpires);
        String atToken = tokenCrypto.encode(TokenType.ACCESS_TOKEN, at);
        String rtToken = tokenCrypto.encode(TokenType.REFRESH_TOKEN, rt);

        long expiresIn = Math.max(0, atExpires.getEpochSecond() - now.getEpochSecond());
        return new TokenResponse(atToken, "Bearer", expiresIn, rtToken, String.join(" ", scope));
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static boolean verifyPkceS256(String verifier, String expectedChallenge) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.US_ASCII),
                    expectedChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
