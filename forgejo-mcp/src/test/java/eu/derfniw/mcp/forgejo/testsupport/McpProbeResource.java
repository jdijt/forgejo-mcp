package eu.derfniw.mcp.forgejo.testsupport;

import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import eu.derfniw.oauthbroker.runtime.security.BearerAuthenticationMechanism;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Test-only probe under the protected {@code /mcp/*} space that echoes the resolved upstream identity
 * from the {@link SecurityIdentity}. Used by {@code EndToEndOAuthFlowTest} to assert the full OAuth
 * dance lands a usable bearer that carries the Forgejo user + upstream token.
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
                "login", entry.upstreamUser().login(),
                "userId", entry.upstreamUser().id(),
                "email", entry.upstreamUser().email(),
                "scope", entry.scope(),
                "upstreamBearer", entry.upstreamTokens().accessToken());
    }
}
