package eu.derfniw.oauthbroker.runtime.spi;

import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.error.UpstreamFailure;

/**
 * The single upstream-specific seam of the broker. OAuth 2.0 standardizes the authorization-code
 * flow (authorize redirect, token exchange, refresh — all config-driven in the broker), but it does
 * <em>not</em> standardize how to resolve a user identity from an access token. The hosting
 * application implements this to call its provider's user-info endpoint (e.g. a
 * {@code /api/v1/user} resource).
 *
 * <p>The broker calls {@link #resolve(String)} in the OAuth callback to set the
 * {@code SecurityIdentity} principal. Implementations should throw {@link UpstreamFailure} on
 * transport/HTTP errors so the broker can surface a clean OAuth error to the client.
 */
public interface UpstreamUserResolver {

    /**
     * Resolve the end user from an upstream access token.
     *
     * @param upstreamAccessToken the provider access token just obtained in the code exchange
     * @return the resolved user
     * @throws UpstreamFailure if the user-info call fails
     */
    UpstreamUser resolve(String upstreamAccessToken);
}
