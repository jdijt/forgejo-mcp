package eu.derfniw.mcp.forgejo.web;

import eu.derfniw.oauthbroker.runtime.config.BrokerConfig;
import eu.derfniw.oauthbroker.runtime.service.BrokerUris;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * One INFO line at startup confirming the effective OAuth topology (issuer, protected resource,
 * upstream authorize endpoint) so operators can spot a misconfigured base URL or upstream without
 * digging. Secrets are never logged.
 */
@ApplicationScoped
public class StartupLogger {

    void onStart(@Observes StartupEvent event, BrokerConfig broker, BrokerUris uris) {
        Log.infof(
                "Forgejo MCP ready: issuer=%s, protected resource=%s, upstream authorize=%s",
                uris.issuer(), uris.protectedResourceUri(), broker.upstream().authorizeUrl());
    }
}
