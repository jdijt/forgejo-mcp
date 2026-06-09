package eu.derfniw.oauthbroker.runtime.error;

/**
 * The request can't proceed and the failure can't be reported through OAuth's error-redirect channel
 * (missing or invalid {@code client_id} / {@code redirect_uri}, an undecryptable {@code state}
 * token, a malformed CIMD, ...). Rendered as 400 with the message as a plain-text body.
 */
public final class BadRequest extends BrokerException {

    public BadRequest(String message) {
        super(message);
    }

    public BadRequest(String message, Throwable cause) {
        super(message, cause);
    }
}
