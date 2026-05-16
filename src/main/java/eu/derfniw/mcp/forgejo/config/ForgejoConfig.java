package eu.derfniw.mcp.forgejo.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.util.List;

@ConfigMapping(prefix = "forgejo")
public interface ForgejoConfig {

    URI baseUrl();

    OAuth oauth();

    interface OAuth {
        String clientId();

        String clientSecret();

        @WithDefault("read:repository,read:issue,read:user")
        List<String> scopes();
    }
}
