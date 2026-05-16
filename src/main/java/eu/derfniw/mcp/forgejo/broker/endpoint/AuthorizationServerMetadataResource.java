package eu.derfniw.mcp.forgejo.broker.endpoint;

import eu.derfniw.mcp.forgejo.broker.model.AuthServerMetadata;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import eu.derfniw.mcp.forgejo.config.ForgejoConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.util.List;

@Path("/.well-known/oauth-authorization-server")
public class AuthorizationServerMetadataResource {

    @Inject
    BrokerConfig broker;

    @Inject
    ForgejoConfig forgejo;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AuthServerMetadata metadata() {
        URI base = broker.publicBaseUrl();
        return new AuthServerMetadata(
                base.toString(),
                resolve(base, "/authorize"),
                resolve(base, "/token"),
                List.of("code"),
                List.of("authorization_code", "refresh_token"),
                List.of("S256"),
                List.of("none"),
                forgejo.oauth().scopes(),
                true);
    }

    private static String resolve(URI base, String path) {
        String b = base.toString();
        return (b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path;
    }
}
