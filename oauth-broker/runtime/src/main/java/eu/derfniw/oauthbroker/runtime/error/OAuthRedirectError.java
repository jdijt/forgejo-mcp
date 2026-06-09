package eu.derfniw.oauthbroker.runtime.error;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * An OAuth error that must be reported to the MCP client through the error-redirect channel
 * (RFC 6749 §4.1.2.1): the mapper turns this into a 302 to {@link #redirectUri} carrying
 * {@code error} / {@code error_description} / the echoed {@code state}. Throwing it requires already
 * knowing the client's redirect URI — pre-redirect validation failures use {@link BadRequest}
 * instead.
 */
public final class OAuthRedirectError extends BrokerException {

    private final URI redirectUri;

    @Nullable
    private final String state;

    private final String errorCode;
    private final String description;

    public OAuthRedirectError(URI redirectUri, @Nullable String state, String errorCode, String description) {
        super(errorCode + ": " + description);
        this.redirectUri = redirectUri;
        this.state = state;
        this.errorCode = errorCode;
        this.description = description;
    }

    public URI redirectUri() {
        return redirectUri;
    }

    @Nullable
    public String state() {
        return state;
    }

    public String errorCode() {
        return errorCode;
    }

    public String description() {
        return description;
    }
}
