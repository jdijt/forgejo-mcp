package eu.derfniw.mcp.forgejo.broker.service;

import eu.derfniw.mcp.forgejo.broker.forgejo.ForgejoOAuthApi;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoTokens;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoUser;
import eu.derfniw.mcp.forgejo.broker.model.UpstreamFailure;
import eu.derfniw.mcp.forgejo.config.ForgejoConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Translates Forgejo's OAuth wire records into our domain model and surfaces transport-level
 * failures as {@link UpstreamFailure}. The HTTP plumbing lives in {@link ForgejoOAuthApi}.
 */
@ApplicationScoped
public class ForgejoOAuthClient {

    private final ForgejoConfig forgejo;
    private final ForgejoOAuthApi api;

    public ForgejoOAuthClient(ForgejoConfig forgejo, @RestClient ForgejoOAuthApi api) {
        this.forgejo = forgejo;
        this.api = api;
    }

    public ForgejoTokens exchangeCode(String code, String redirectUri) {
        ForgejoOAuthApi.TokenResponse resp;
        try {
            resp = api.exchangeAuthorizationCode(
                    "authorization_code",
                    code,
                    redirectUri,
                    forgejo.oauth().clientId(),
                    forgejo.oauth().clientSecret());
        } catch (WebApplicationException e) {
            throw new UpstreamFailure(
                    "Forgejo token exchange failed: HTTP " + e.getResponse().getStatus(), e);
        }
        Instant expiresAt = Instant.now().plusSeconds(Math.max(0, resp.expiresIn()));
        return new ForgejoTokens(resp.accessToken(), resp.refreshToken(), expiresAt);
    }

    public ForgejoTokens refresh(String refreshToken) {
        ForgejoOAuthApi.TokenResponse resp;
        try {
            resp = api.refreshAccessToken(
                    "refresh_token",
                    refreshToken,
                    forgejo.oauth().clientId(),
                    forgejo.oauth().clientSecret());
        } catch (WebApplicationException e) {
            throw new UpstreamFailure(
                    "Forgejo token refresh failed: HTTP " + e.getResponse().getStatus(), e);
        }
        Instant expiresAt = Instant.now().plusSeconds(Math.max(0, resp.expiresIn()));
        return new ForgejoTokens(resp.accessToken(), resp.refreshToken(), expiresAt);
    }

    public ForgejoUser fetchUser(String accessToken) {
        ForgejoOAuthApi.UserResponse u;
        try {
            u = api.currentUser("Bearer " + accessToken);
        } catch (WebApplicationException e) {
            throw new UpstreamFailure(
                    "Forgejo /api/v1/user failed: HTTP " + e.getResponse().getStatus(), e);
        }
        return new ForgejoUser(u.id(), u.login(), u.email());
    }
}
