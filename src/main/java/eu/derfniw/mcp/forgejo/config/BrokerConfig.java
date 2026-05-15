package eu.derfniw.mcp.forgejo.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "broker")
public interface BrokerConfig {

    URI publicBaseUrl();

    @WithDefault("PT1H")
    Duration accessTokenTtl();

    @WithDefault("PT60S")
    Duration authCodeTtl();

    @WithDefault("P30D")
    Duration refreshTokenTtl();

    Cimd cimd();

    interface Cimd {
        Optional<List<String>> allowedHosts();

        @WithDefault("PT10S")
        Duration fetchTimeout();
    }
}
