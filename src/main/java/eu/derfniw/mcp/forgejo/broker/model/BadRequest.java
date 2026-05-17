package eu.derfniw.mcp.forgejo.broker.model;

/**
 * Generic bad request — used when the failure can't be reported via OAuth's error-redirect channel
 * (missing or invalid {@code client_id} / {@code redirect_uri}, undecryptable {@code state} token,
 * malformed CIMD, ...). Rendered by the endpoint layer as 400 with the message as the body.
 */
public sealed class BadRequest extends ClientError permits TokenCryptoException, CimdValidationError {

    public BadRequest(String message) {
        super(message);
    }

    public BadRequest(String message, Throwable cause) {
        super(message, cause);
    }
}
