package eu.derfniw.oauthbroker.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "broker")
public interface BrokerConfig {

    /** Public URL where this broker is reachable; the OAuth issuer and advertised URLs derive from it. No trailing slash. */
    URI publicBaseUrl();

    /** base64url-encoded 32-byte AES key used for envelope encryption of broker tokens. */
    String tokenEncryptionKey();

    /**
     * Path of the bearer-protected resource this broker fronts, relative to {@link #publicBaseUrl()}.
     * It is the resource identifier advertised in the protected-resource metadata and the suffix of
     * the RFC 9728 {@code /.well-known/oauth-protected-resource} document. Must start with a slash.
     */
    @WithDefault("/mcp")
    String protectedResourcePath();

    /** Lifetime of an in-flight authorize request (the encrypted {@code state} round-tripped through the upstream). */
    @WithDefault("PT10M")
    Duration pendingAuthTtl();

    /** Lifetime of issued broker access tokens, capped further by the upstream token's own expiry. */
    @WithDefault("PT1H")
    Duration accessTokenTtl();

    /** Lifetime of a broker authorization code between the callback and the token exchange. */
    @WithDefault("PT60S")
    Duration authCodeTtl();

    /** Lifetime of issued broker refresh tokens. */
    @WithDefault("P30D")
    Duration refreshTokenTtl();

    /** Upstream OAuth 2.0 provider the broker brokers against (confidential-client side). */
    Upstream upstream();

    /** Client ID Metadata Document (CIMD) resolution settings. */
    Cimd cimd();

    /**
     * Upstream OAuth 2.0 provider settings. The authorization-code flow is standardized, so the
     * broker drives it generically from these values; the only provider-specific code is the
     * application's {@code UpstreamUserResolver}.
     */
    interface Upstream {
        /** Upstream authorization endpoint the broker redirects the user-agent to. */
        URI authorizeUrl();

        /** Upstream token endpoint used for the code exchange and refresh grants. */
        URI tokenUrl();

        /** Client ID of the confidential OAuth app the broker holds for the upstream. */
        String clientId();

        /** Client secret of the confidential OAuth app the broker holds for the upstream. */
        String clientSecret();

        /** Scopes the broker requests from the upstream and advertises downstream. */
        List<String> scopes();
    }

    /** CIMD fetch behaviour. */
    interface Cimd {
        /** Optional allowlist of hosts a CIMD {@code client_id} URL may point at; unset means any host is allowed. */
        Optional<List<String>> allowedHosts();

        /** Timeout for the outbound CIMD document fetch. */
        @WithDefault("PT10S")
        Duration fetchTimeout();
    }
}
