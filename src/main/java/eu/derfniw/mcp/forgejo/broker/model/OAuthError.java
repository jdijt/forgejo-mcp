package eu.derfniw.mcp.forgejo.broker.model;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * OAuth-spec error that must be reported back to the MCP client through the error-redirect channel
 * (RFC 6749 §4.1.2.1): 302 to {@link #redirectUri} with {@code error}/{@code error_description}/
 * (echoed) {@code state}. Throwing this requires already knowing the client's redirect URI —
 * pre-redirect validation failures should use {@link BadRequest} instead.
 */
public final class OAuthError extends ClientError {

    private final URI redirectUri;

    @Nullable
    private final String state;

    private final String errorCode;
    private final String description;

    public OAuthError(URI redirectUri, @Nullable String state, String errorCode, String description) {
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
