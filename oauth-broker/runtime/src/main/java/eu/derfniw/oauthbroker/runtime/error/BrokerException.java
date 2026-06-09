package eu.derfniw.oauthbroker.runtime.error;

/**
 * Root of the broker's domain-level exception hierarchy. Services and resources throw these
 * directly; the single {@code BrokerExceptionMapper} renders each leaf to its HTTP response. The
 * sealed permit list is exactly the set of renderings the mapper knows about, so adding a leaf is a
 * compile error there until its rendering is decided. Technical failures (IO, crypto, ...) are not
 * modelled here — they are translated to one of these at the boundary where the domain meaning
 * becomes clear.
 */
public abstract sealed class BrokerException extends RuntimeException
        permits BadRequest, OAuthRedirectError, TokenError, UpstreamFailure {

    protected BrokerException(String message) {
        super(message);
    }

    protected BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
