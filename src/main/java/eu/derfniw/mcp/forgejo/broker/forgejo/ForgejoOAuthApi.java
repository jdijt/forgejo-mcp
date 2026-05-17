package eu.derfniw.mcp.forgejo.broker.forgejo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Typed REST client for the subset of Forgejo's OAuth surface the broker uses server-to-server:
 * the token endpoint (authorization-code exchange + refresh) and {@code /api/v1/user} for
 * resolving the authenticated user. The base URL is configured under {@code quarkus.rest-client
 * .forgejo-oauth.url}.
 */
@RegisterRestClient(configKey = "forgejo-oauth")
public interface ForgejoOAuthApi {

    @POST
    @Path("/login/oauth/access_token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    TokenResponse exchangeAuthorizationCode(
            @FormParam("grant_type") String grantType,
            @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret);

    @GET
    @Path("/api/v1/user")
    @Produces(MediaType.APPLICATION_JSON)
    UserResponse currentUser(@HeaderParam("Authorization") String bearer);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(long id, String login, String email) {}
}
