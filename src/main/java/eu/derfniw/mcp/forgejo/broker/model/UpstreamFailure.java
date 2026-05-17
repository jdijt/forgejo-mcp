package eu.derfniw.mcp.forgejo.broker.model;

/**
 * An upstream call (Forgejo, CIMD host fetch, ...) failed in a way we couldn't translate into a
 * meaningful caller-facing error. Rendered by the endpoint layer as 502.
 */
public final class UpstreamFailure extends ServerError {

    public UpstreamFailure(String message) {
        super(message);
    }

    public UpstreamFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
