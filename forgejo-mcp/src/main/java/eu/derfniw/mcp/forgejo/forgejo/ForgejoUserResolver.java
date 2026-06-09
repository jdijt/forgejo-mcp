package eu.derfniw.mcp.forgejo.forgejo;

import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.error.UpstreamFailure;
import eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Forgejo implementation of the broker's one upstream-specific seam: resolving the end user from an
 * upstream access token via {@code GET /api/v1/user}. The broker calls this in the OAuth callback to
 * set the {@code SecurityIdentity} principal.
 */
@ApplicationScoped
public class ForgejoUserResolver implements UpstreamUserResolver {

    @Inject
    @RestClient
    ForgejoUserApi userApi;

    @Override
    public UpstreamUser resolve(String upstreamAccessToken) {
        try {
            ForgejoUserApi.UserResponse user = userApi.currentUser("Bearer " + upstreamAccessToken);
            return new UpstreamUser(user.id(), user.login(), user.email());
        } catch (UpstreamFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UpstreamFailure("Forgejo user-info call failed", e);
        }
    }
}
