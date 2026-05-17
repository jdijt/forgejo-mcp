package eu.derfniw.mcp.forgejo.broker.model;

/**
 * Root of the broker's domain-level exception hierarchy. Services throw these directly; the
 * endpoint layer renders them via a single {@code ExceptionMapper}. Anything technical
 * (IOException, GeneralSecurityException, ...) is translated to one of these at the layer where
 * the domain meaning becomes clear — never propagated up to the resource.
 */
public abstract sealed class BrokerException extends RuntimeException permits ClientError, ServerError {

    protected BrokerException(String message) {
        super(message);
    }

    protected BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
