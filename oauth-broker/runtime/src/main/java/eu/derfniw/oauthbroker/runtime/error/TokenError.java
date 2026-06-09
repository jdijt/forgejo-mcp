package eu.derfniw.oauthbroker.runtime.error;

/**
 * An OAuth token-endpoint error (RFC 6749 §5.2). Rendered as 400 with a JSON body of
 * {@code {"error": ..., "error_description": ...}} — the shape the spec mandates for
 * {@code POST /token} (instead of an error redirect).
 */
public final class TokenError extends BrokerException {

    private final String errorCode;
    private final String description;

    public TokenError(String errorCode, String description) {
        super(errorCode + ": " + description);
        this.errorCode = errorCode;
        this.description = description;
    }

    public TokenError(String errorCode, String description, Throwable cause) {
        super(errorCode + ": " + description, cause);
        this.errorCode = errorCode;
        this.description = description;
    }

    public String errorCode() {
        return errorCode;
    }

    public String description() {
        return description;
    }
}
