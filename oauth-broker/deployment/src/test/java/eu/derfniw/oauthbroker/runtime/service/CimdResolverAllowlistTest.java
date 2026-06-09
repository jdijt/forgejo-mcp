package eu.derfniw.oauthbroker.runtime.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.derfniw.oauthbroker.runtime.error.BadRequest;
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
        BadRequest e = assertThrows(BadRequest.class, () -> resolver.resolve("http://127.0.0.1:1/some.json"));
        assertTrue(e.getMessage().contains("Host not allowed"), e.getMessage());
    }

    public static class AllowlistProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("broker.cimd.allowed-hosts", "claude.ai,claude.com");
        }
    }
}
