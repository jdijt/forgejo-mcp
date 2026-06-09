package eu.derfniw.oauthbroker.runtime.security;

import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Per-request access to the upstream provider's bearer token. The token is stashed on the
 * {@link SecurityIdentity} by {@link BearerAuthenticationMechanism} when it decrypts the inbound
 * {@code mcp_at_*} envelope; consuming applications inject this bean to forward upstream calls as
 * the same user (e.g. from REST clients).
 *
 * <p>Methods throw {@link IllegalStateException} if invoked outside an authenticated request
 * (anonymous identity or missing attribute) — that's a programming error, not a user error, since
 * the protected paths are bearer-gated by config.
 */
@RequestScoped
public class UpstreamBearer {

    @Inject
    SecurityIdentity identity;

    public String token() {
        if (identity.isAnonymous()) {
            throw new IllegalStateException(
                    "UpstreamBearer accessed outside an authenticated request (anonymous SecurityIdentity)");
        }
        Object value = identity.getAttribute(BearerAuthenticationMechanism.ATTR_ENTRY);
        if (!(value instanceof AccessTokenEntry entry)) {
            throw new IllegalStateException(
                    "SecurityIdentity is authenticated but carries no decoded access-token entry");
        }
        String bearer = entry.upstreamTokens().accessToken();
        if (bearer.isBlank()) {
            throw new IllegalStateException("Access-token entry carries no upstream bearer");
        }
        return bearer;
    }

    public String header() {
        return "Bearer " + token();
    }
}
