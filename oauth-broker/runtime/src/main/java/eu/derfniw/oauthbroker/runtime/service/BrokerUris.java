package eu.derfniw.oauthbroker.runtime.service;

import eu.derfniw.oauthbroker.runtime.config.BrokerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;

/**
 * All absolute URLs the broker publishes, derived once from {@link BrokerConfig#publicBaseUrl()}.
 */
@ApplicationScoped
public class BrokerUris {

    /** Fixed prefix of the RFC 9728 protected-resource metadata document. */
    private static final String PRM_WELL_KNOWN_PREFIX = "/.well-known/oauth-protected-resource";

    private final URI issuer;
    private final URI authorize;
    private final URI token;
    private final URI callback;
    private final String protectedResourcePath;
    private final URI protectedResource;
    private final URI protectedResourceMetadata;

    public BrokerUris(BrokerConfig broker) {
        this.issuer = broker.publicBaseUrl();
        this.authorize = UriBuilder.fromUri(issuer).path("authorize").build();
        this.token = UriBuilder.fromUri(issuer).path("token").build();
        this.callback =
                UriBuilder.fromUri(issuer).path("oauth").path("callback").build();
        this.protectedResourcePath = normalize(broker.protectedResourcePath());
        this.protectedResource =
                UriBuilder.fromUri(issuer).path(protectedResourcePath).build();
        this.protectedResourceMetadata = UriBuilder.fromUri(issuer)
                .path(PRM_WELL_KNOWN_PREFIX + protectedResourcePath)
                .build();
    }

    private static String normalize(String path) {
        String trimmed = path.strip();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
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

    /** {@code /oauth/callback} — registered with the upstream provider for this deployment. */
    public URI callbackUri() {
        return callback;
    }

    /** The configured protected-resource path, normalized to start with a slash (e.g. {@code /mcp}). */
    public String protectedResourcePath() {
        return protectedResourcePath;
    }

    /** The protected resource URL advertised in PRM metadata. */
    public URI protectedResourceUri() {
        return protectedResource;
    }

    /** The RFC 9728 {@code /.well-known/oauth-protected-resource} document URL for the protected resource. */
    public URI protectedResourceMetadataUri() {
        return protectedResourceMetadata;
    }
}
