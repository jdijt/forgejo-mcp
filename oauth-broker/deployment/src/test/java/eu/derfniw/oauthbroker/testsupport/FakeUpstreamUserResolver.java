package eu.derfniw.oauthbroker.testsupport;

import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Trivial {@link UpstreamUserResolver} so the broker's {@code OAuthResource} has a satisfiable
 * injection point (and the build-time SPI check passes) when the generic broker tests run without a
 * real upstream. The broker tests never drive the OAuth callback against a real provider, so
 * {@link #resolve(String)} is not exercised — the provider-backed resolver and its end-to-end
 * coverage live in the consuming application module.
 */
@ApplicationScoped
public class FakeUpstreamUserResolver implements UpstreamUserResolver {

    @Override
    public UpstreamUser resolve(String upstreamAccessToken) {
        return new UpstreamUser(1L, "fake-user", "fake-user@test.local");
    }
}
