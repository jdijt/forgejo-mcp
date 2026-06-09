package eu.derfniw.oauthbroker.testsupport;

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
 * Test-only probe under the default protected {@code /mcp/*} space that echoes the resolved upstream
 * identity from the {@link SecurityIdentity}. Used by the bearer-auth tests to prove a decoded
 * {@code mcp_at_*} bearer carries the embedded upstream bearer + user through to request-scoped
 * consumers. Each consuming app writes its own probe for whatever path it actually protects.
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
