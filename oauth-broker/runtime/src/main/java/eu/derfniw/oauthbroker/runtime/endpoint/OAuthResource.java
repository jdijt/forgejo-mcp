package eu.derfniw.oauthbroker.runtime.endpoint;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.config.BrokerConfig;
import eu.derfniw.oauthbroker.runtime.crypto.TokenCrypto;
import eu.derfniw.oauthbroker.runtime.crypto.TokenCryptoException;
import eu.derfniw.oauthbroker.runtime.crypto.TokenType;
import eu.derfniw.oauthbroker.runtime.dto.AuthServerMetadata;
import eu.derfniw.oauthbroker.runtime.dto.CimdDocument;
import eu.derfniw.oauthbroker.runtime.dto.ProtectedResourceMetadata;
import eu.derfniw.oauthbroker.runtime.dto.TokenResponse;
import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import eu.derfniw.oauthbroker.runtime.envelope.AuthCodeEntry;
import eu.derfniw.oauthbroker.runtime.envelope.PendingAuth;
import eu.derfniw.oauthbroker.runtime.envelope.RefreshTokenEntry;
import eu.derfniw.oauthbroker.runtime.error.BadRequest;
import eu.derfniw.oauthbroker.runtime.error.OAuthRedirectError;
import eu.derfniw.oauthbroker.runtime.error.TokenError;
import eu.derfniw.oauthbroker.runtime.error.UpstreamFailure;
import eu.derfniw.oauthbroker.runtime.service.BrokerUris;
import eu.derfniw.oauthbroker.runtime.service.CimdResolver;
import eu.derfniw.oauthbroker.runtime.service.UpstreamOAuthClient;
import eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The broker's downstream OAuth surface in a single resource: discovery metadata
 * ({@code /.well-known/*}), the authorization endpoint, and the upstream callback. The OAuth flow is
 * standardized and config-driven; the only upstream-specific seam is the {@link UpstreamUserResolver}
 * called in the callback to resolve the identity. Error paths throw
 * {@link eu.derfniw.oauthbroker.runtime.error.BrokerException} subtypes — see
 * {@link BrokerExceptionMapper}. This resource only translates technical exceptions
 * ({@link TokenCryptoException}, {@link IllegalArgumentException}) or attaches request-specific
 * context to upstream failures ({@link UpstreamFailure} → {@link OAuthRedirectError} in the callback, where
 * the client's {@code redirect_uri} is known).
 */
@Path("/")
public class OAuthResource {

    @Inject
    BrokerConfig broker;

    @Inject
    BrokerUris brokerUris;

    @Inject
    CimdResolver cimdResolver;

    @Inject
    TokenCrypto tokenCrypto;

    @Inject
    UpstreamOAuthClient upstreamOAuth;

    @Inject
    UpstreamUserResolver userResolver;

    // ---------------------------------------------------------------------
    // Discovery metadata
    // ---------------------------------------------------------------------

    private static List<String> parseScopes(@Nullable String scope) {
        if (scope == null || scope.isBlank()) return List.of();
        return Arrays.stream(scope.trim().split("\\s+")).distinct().toList();
    }

    /**
     * Whether {@code requested} is registered in the client's CIMD. An exact match always passes.
     * Additionally, per RFC 8252 §7.3, a loopback redirect (localhost / 127.0.0.1 / ::1) matches a
     * registered loopback URI ignoring the port — native clients bind an OS-assigned ephemeral port
     * at request time, so the authorization server must accept any port on the loopback interface.
     */
    private static boolean isRegisteredRedirect(URI requested, List<URI> registered) {
        if (registered.contains(requested)) {
            return true;
        }
        if (!isLoopbackHost(requested.getHost())) {
            return false;
        }
        return registered.stream().anyMatch(r -> loopbackMatchIgnoringPort(r, requested));
    }

    private static boolean loopbackMatchIgnoringPort(URI registered, URI requested) {
        return isLoopbackHost(registered.getHost())
                && eqIgnoreCase(registered.getScheme(), requested.getScheme())
                && eqIgnoreCase(registered.getHost(), requested.getHost())
                && Objects.equals(registered.getPath(), requested.getPath())
                && Objects.equals(registered.getRawQuery(), requested.getRawQuery());
    }

    private static boolean isLoopbackHost(@Nullable String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static boolean eqIgnoreCase(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
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
                broker.upstream().scopes(),
                true);
    }

    @GET
    @Path("/.well-known/oauth-protected-resource/{resource:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public ProtectedResourceMetadata protectedResourceMetadata(@PathParam("resource") String resource) {
        // RFC 9728: the metadata lives under the well-known prefix + the resource's path. Only the
        // configured protected resource resolves; anything else is not a resource we front.
        if (!("/" + resource).equals(brokerUris.protectedResourcePath())) {
            throw new NotFoundException();
        }
        return new ProtectedResourceMetadata(
                brokerUris.protectedResourceUri().toString(),
                List.of(brokerUris.issuer().toString()),
                List.of("header"),
                broker.upstream().scopes());
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
        if (!isRegisteredRedirect(redirect, cimd.redirectUris())) {
            throw new BadRequest("redirect_uri not registered in client metadata");
        }

        if (!"code".equals(responseType)) {
            throw new OAuthRedirectError(redirect, state, "unsupported_response_type", "response_type must be 'code'");
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new OAuthRedirectError(redirect, state, "invalid_request", "code_challenge is required");
        }
        // Intellij marks this null check as unneeded,
        // but unfortunately it is needed to satisfy NullAway :).
        if (codeChallengeMethod == null || !"S256".equals(codeChallengeMethod)) {
            throw new OAuthRedirectError(redirect, state, "invalid_request", "code_challenge_method must be S256");
        }

        List<String> requestedScopes = parseScopes(scope);
        List<String> advertised = broker.upstream().scopes();
        if (!requestedScopes.isEmpty() && !new HashSet<>(advertised).containsAll(requestedScopes)) {
            throw new OAuthRedirectError(redirect, state, "invalid_scope", "requested scope exceeds advertised set");
        }
        // Downstream scope (roles on the issued bearer, echoed in the token response) reflects only
        // what the client requested. Upstream we always request the full configured scope set — it
        // includes whatever the UpstreamUserResolver needs to resolve the identity.
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

        URI upstreamAuthorize = UriBuilder.fromUri(broker.upstream().authorizeUrl())
                .queryParam("client_id", broker.upstream().clientId())
                .queryParam("redirect_uri", brokerUris.callbackUri())
                .queryParam("response_type", "code")
                .queryParam("state", stateToken)
                .queryParam("scope", String.join(" ", broker.upstream().scopes()))
                .build();
        Log.debugf("Bouncing user to upstream authorize: %s", upstreamAuthorize);
        return Response.status(Response.Status.FOUND)
                .location(upstreamAuthorize)
                .build();
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
        PendingAuth pending;
        try {
            pending = tokenCrypto.decode(TokenType.PENDING_AUTH, state, PendingAuth.class);
        } catch (TokenCryptoException e) {
            // We can't trust the redirect_uri inside an undecryptable state, so there's no OAuth
            // redirect channel to report through — surface a plain 400.
            throw new BadRequest("state is invalid or expired", e);
        }

        if (error != null && !error.isBlank()) {
            throw new OAuthRedirectError(
                    pending.redirectUri(), pending.mcpState(), error, errorDescription == null ? "" : errorDescription);
        }

        if (code == null || code.isBlank()) {
            throw new OAuthRedirectError(pending.redirectUri(), pending.mcpState(), "invalid_request", "missing code");
        }

        UpstreamTokens tokens;
        UpstreamUser user;
        try {
            tokens = upstreamOAuth.exchangeCode(code, brokerUris.callbackUri().toString());
            user = userResolver.resolve(tokens.accessToken());
        } catch (UpstreamFailure e) {
            // Upstream failed; we have enough context to tell the client via the OAuth redirect.
            // Log the underlying failure so operators can diagnose — the redirect alone is opaque.
            Log.warnf(e, "Upstream failed during /oauth/callback; rendering server_error to client");
            throw new OAuthRedirectError(
                    pending.redirectUri(), pending.mcpState(), "server_error", "upstream exchange failed");
        }
        Log.infof(
                "OAuth callback: authenticated upstream user '%s' for client %s", user.login(), pending.mcpClientId());

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
            Log.debugf("Rejecting authorization_code grant: %s", e.getMessage());
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

        return mintTokens(entry.mcpClientId(), entry.scope(), entry.upstreamTokens(), entry.upstreamUser());
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
            Log.debugf("Rejecting refresh_token grant: %s", e.getMessage());
            throw new TokenError("invalid_grant", "refresh_token is invalid or expired", e);
        }

        if (!entry.mcpClientId().equals(clientId)) {
            throw new TokenError("invalid_grant", "client_id does not match the issued refresh_token");
        }

        UpstreamTokens fresh;
        try {
            fresh = upstreamOAuth.refresh(entry.upstreamTokens().refreshToken());
        } catch (UpstreamFailure e) {
            // RFC 6749 §5.2: surface upstream refresh failure as invalid_grant so the client re-authorizes.
            // Converted before reaching BrokerExceptionMapper, so log here — this is the only record.
            Log.warnf(e, "Upstream refresh failed for client %s; responding invalid_grant", clientId);
            throw new TokenError("invalid_grant", "upstream refresh failed", e);
        }

        return mintTokens(entry.mcpClientId(), entry.scope(), fresh, entry.upstreamUser());
    }

    private TokenResponse mintTokens(
            String mcpClientId, List<String> scope, UpstreamTokens upstreamTokens, UpstreamUser upstreamUser) {
        Instant now = Instant.now();
        // Broker AT TTL is capped by the upstream AT lifetime so a single decode covers the call.
        Instant atExpires = earliest(now.plus(broker.accessTokenTtl()), upstreamTokens.accessExpiresAt());
        Instant rtExpires = now.plus(broker.refreshTokenTtl());

        AccessTokenEntry at = new AccessTokenEntry(mcpClientId, scope, upstreamTokens, upstreamUser, atExpires);
        RefreshTokenEntry rt = new RefreshTokenEntry(mcpClientId, scope, upstreamTokens, upstreamUser, rtExpires);
        String atToken = tokenCrypto.encode(TokenType.ACCESS_TOKEN, at);
        String rtToken = tokenCrypto.encode(TokenType.REFRESH_TOKEN, rt);

        long expiresIn = Math.max(0, atExpires.getEpochSecond() - now.getEpochSecond());
        Log.infof(
                "Issued tokens for user '%s' (client %s, scope [%s])",
                upstreamUser.login(), mcpClientId, String.join(" ", scope));
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
