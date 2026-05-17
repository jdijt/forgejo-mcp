package eu.derfniw.mcp.forgejo.broker.model;

/** The caller did something wrong: malformed request, invalid credentials, expired state, etc. */
public abstract sealed class ClientError extends BrokerException permits BadRequest, OAuthError {

    protected ClientError(String message) {
        super(message);
    }

    protected ClientError(String message, Throwable cause) {
        super(message, cause);
    }
}
