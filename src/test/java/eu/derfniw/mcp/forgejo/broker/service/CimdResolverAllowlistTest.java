package eu.derfniw.mcp.forgejo.broker.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CimdResolverAllowlistTest.AllowlistProfile.class)
class CimdResolverAllowlistTest {

    @Inject
    CimdResolver resolver;

    @Test
    void rejectsHostNotInAllowlistBeforeFetch() {
        CimdException e = assertThrows(CimdException.class, () -> resolver.resolve("http://127.0.0.1:1/some.json"));
        assertTrue(e.getMessage().contains("not in allowlist"), e.getMessage());
    }

    public static class AllowlistProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("broker.cimd.allowed-hosts", "claude.ai,claude.com");
        }
    }
}
