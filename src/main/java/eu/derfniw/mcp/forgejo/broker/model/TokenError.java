package eu.derfniw.mcp.forgejo.broker.model;

/**
 * OAuth token-endpoint error (RFC 6749 §5.2). Rendered as 400 with a JSON body of
 * {@code {"error": ..., "error_description": ...}}. Used for failures on {@code POST /token}
 * where the spec mandates a JSON response instead of an error redirect.
 */
public final class TokenError extends ClientError {

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
