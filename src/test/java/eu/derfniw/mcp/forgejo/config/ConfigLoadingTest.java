package eu.derfniw.mcp.forgejo.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ConfigLoadingTest {

    @Inject
    ForgejoConfig forgejo;

    @Inject
    BrokerConfig broker;

    @Test
    void forgejoConfigBindsTestProfileValues() {
        assertEquals(URI.create("https://forgejo.test"), forgejo.baseUrl());
        assertEquals("test-client-id", forgejo.oauth().clientId());
        assertEquals("test-client-secret", forgejo.oauth().clientSecret());
    }

    @Test
    void forgejoOAuthScopesDefaultsApply() {
        assertNotNull(forgejo.oauth().scopes());
        assertFalse(forgejo.oauth().scopes().isEmpty(), "default scopes should populate when unset");
        assertTrue(forgejo.oauth().scopes().contains("read:repository"));
    }

    @Test
    void brokerConfigBindsAndAppliesDefaults() {
        assertEquals(URI.create("http://localhost:8081"), broker.publicBaseUrl());
        assertEquals(Duration.ofHours(1), broker.accessTokenTtl());
        assertEquals(Duration.ofSeconds(60), broker.authCodeTtl());
        assertEquals(Duration.ofDays(30), broker.refreshTokenTtl());
        assertEquals(Duration.ofSeconds(10), broker.cimd().fetchTimeout());
        assertTrue(broker.cimd().allowedHosts().isEmpty(), "no CIMD allowlist by default");
    }
}
