package eu.derfniw.mcp.forgejo.broker.model;

/** Something on our side (or our upstream) failed — not the caller's fault. */
public abstract sealed class ServerError extends BrokerException permits UpstreamFailure {

    protected ServerError(String message) {
        super(message);
    }

    protected ServerError(String message, Throwable cause) {
        super(message, cause);
    }
}
