package eu.derfniw.mcp.forgejo.broker.service;

import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;

/**
 * All absolute URLs the broker publishes, derived once from {@link BrokerConfig#publicBaseUrl()}.
 */
@ApplicationScoped
public class BrokerUris {

    private final URI issuer;
    private final URI authorize;
    private final URI token;
    private final URI callback;
    private final URI mcpResource;

    public BrokerUris(BrokerConfig broker) {
        this.issuer = broker.publicBaseUrl();
        this.authorize = UriBuilder.fromUri(issuer).path("authorize").build();
        this.token = UriBuilder.fromUri(issuer).path("token").build();
        this.callback =
                UriBuilder.fromUri(issuer).path("oauth").path("callback").build();
        this.mcpResource = UriBuilder.fromUri(issuer).path("mcp").build();
    }

    /** OAuth issuer = {@code publicBaseUrl}. */
    public URI issuer() {
        return issuer;
    }

    /** {@code /authorize}. */
    public URI authorizeUri() {
        return authorize;
    }

    /** {@code /token}. */
    public URI tokenUri() {
        return token;
    }

    /** {@code /oauth/callback} — registered with Forgejo for this deployment. */
    public URI callbackUri() {
        return callback;
    }

    /** The protected MCP resource URL advertised in PRM metadata. */
    public URI mcpResourceUri() {
        return mcpResource;
    }
}
