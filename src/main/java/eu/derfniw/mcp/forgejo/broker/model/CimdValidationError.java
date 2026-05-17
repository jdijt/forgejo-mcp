package eu.derfniw.mcp.forgejo.broker.model;

public final class CimdValidationError extends BadRequest {

    public CimdValidationError(String message) {
        super(message);
    }

    public CimdValidationError(String message, Throwable cause) {
        super(message, cause);
    }
}
