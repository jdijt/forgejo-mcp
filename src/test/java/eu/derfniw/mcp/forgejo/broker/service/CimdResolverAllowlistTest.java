package eu.derfniw.mcp.forgejo.broker.service;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CimdResolverAllowlistTest.AllowlistProfile.class)
class CimdResolverAllowlistTest {

    @Inject CimdResolver resolver;

    @Test
    void rejectsHostNotInAllowlistBeforeFetch() {
        CimdException e = assertThrows(CimdException.class,
                () -> resolver.resolve("http://127.0.0.1:1/some.json"));
        assertTrue(e.getMessage().contains("not in allowlist"), e.getMessage());
    }

    public static class AllowlistProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("broker.cimd.allowed-hosts", "claude.ai,claude.com");
        }
    }
}
