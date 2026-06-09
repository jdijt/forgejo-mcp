package eu.derfniw.mcp.forgejo.forgejo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Typed client for Forgejo's {@code /api/v1/user} endpoint, used to resolve the authenticated user
 * after the OAuth code exchange. Shares the {@code forgejo-api} base URL with {@link ForgejoReposApi}.
 */
@RegisterRestClient(configKey = "forgejo-api")
public interface ForgejoUserApi {

    @GET
    @Path("/api/v1/user")
    @Produces(MediaType.APPLICATION_JSON)
    UserResponse currentUser(@HeaderParam("Authorization") String bearer);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(long id, String login, String email) {}
}
