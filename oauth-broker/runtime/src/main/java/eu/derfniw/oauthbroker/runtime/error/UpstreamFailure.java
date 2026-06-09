package eu.derfniw.oauthbroker.runtime.error;

/**
 * An upstream call (provider token / user-info, CIMD host fetch, ...) failed in a way we can't
 * translate into a meaningful caller-facing error. Rendered as 502. Implementations of the
 * {@code UpstreamUserResolver} SPI throw this on transport/HTTP failures.
 */
public final class UpstreamFailure extends BrokerException {

    public UpstreamFailure(String message) {
        super(message);
    }

    public UpstreamFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
