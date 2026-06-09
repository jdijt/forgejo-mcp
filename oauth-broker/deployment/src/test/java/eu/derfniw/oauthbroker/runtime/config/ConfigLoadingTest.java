package eu.derfniw.oauthbroker.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ConfigLoadingTest {

    @Inject
    BrokerConfig broker;

    @Test
    void upstreamConfigBindsValues() {
        assertEquals(
                URI.create("https://upstream.test/login/oauth/authorize"),
                broker.upstream().authorizeUrl());
        assertEquals(
                URI.create("https://upstream.test/login/oauth/access_token"),
                broker.upstream().tokenUrl());
        assertEquals("test-client-id", broker.upstream().clientId());
        assertEquals("test-client-secret", broker.upstream().clientSecret());
    }

    @Test
    void upstreamScopesBind() {
        assertNotNull(broker.upstream().scopes());
        assertFalse(broker.upstream().scopes().isEmpty(), "configured scopes should populate");
        assertTrue(broker.upstream().scopes().contains("read:repository"));
    }

    @Test
    void brokerConfigBindsAndAppliesDefaults() {
        assertEquals(URI.create("http://localhost:8081"), broker.publicBaseUrl());
        assertEquals(Duration.ofMinutes(10), broker.pendingAuthTtl());
        assertEquals(Duration.ofHours(1), broker.accessTokenTtl());
        assertEquals(Duration.ofSeconds(60), broker.authCodeTtl());
        assertEquals(Duration.ofDays(30), broker.refreshTokenTtl());
        assertEquals(Duration.ofSeconds(10), broker.cimd().fetchTimeout());
        assertTrue(broker.cimd().allowedHosts().isEmpty(), "no CIMD allowlist by default");
        assertFalse(broker.tokenEncryptionKey().isBlank(), "test profile must supply an encryption key");
    }
}
