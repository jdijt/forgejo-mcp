package eu.derfniw.mcp.forgejo.broker.endpoint;

import eu.derfniw.mcp.forgejo.broker.model.ProtectedResourceMetadata;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import eu.derfniw.mcp.forgejo.config.ForgejoConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.net.URI;
import java.util.List;

/**
 * RFC 9728 PRM document for the MCP endpoint at {publicBaseUrl}/mcp.
 * Discovery URL: /.well-known/oauth-protected-resource/mcp
 */
@Path("/.well-known/oauth-protected-resource/mcp")
public class ProtectedResourceMetadataResource {

    @Inject BrokerConfig broker;
    @Inject ForgejoConfig forgejo;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ProtectedResourceMetadata metadata() {
        URI base = broker.publicBaseUrl();
        String b = base.toString();
        String trimmed = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return new ProtectedResourceMetadata(
                trimmed + "/mcp",
                List.of(trimmed),
                List.of("header"),
                forgejo.oauth().scopes());
    }
}
