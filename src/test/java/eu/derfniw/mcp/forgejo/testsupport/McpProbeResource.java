package eu.derfniw.mcp.forgejo.testsupport;

import eu.derfniw.mcp.forgejo.broker.model.AccessTokenEntry;
import eu.derfniw.mcp.forgejo.broker.security.BearerAuthenticationMechanism;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Test-only probe under {@code /mcp/*} that returns the resolved Forgejo identity. Used by the
 * bearer-auth filter tests (Phase 1.5) and the end-to-end happy-path test (Phase 1.6) to prove the
 * decoded token's embedded Forgejo bearer + user flow through to request-scoped consumers.
 */
@Path("/mcp/_probe/whoami")
public class McpProbeResource {

    @Inject
    SecurityIdentity identity;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> whoami() {
        AccessTokenEntry entry = (AccessTokenEntry) identity.getAttribute(BearerAuthenticationMechanism.ATTR_ENTRY);
        return Map.of(
                "principal", identity.getPrincipal().getName(),
                "login", entry.forgejoUser().login(),
                "userId", entry.forgejoUser().id(),
                "email", entry.forgejoUser().email(),
                "scope", entry.scope(),
                "forgejoBearer", entry.forgejoTokens().accessToken());
    }
}
