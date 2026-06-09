package eu.derfniw.oauthbroker.runtime.api;

/**
 * The authenticated end user as resolved from the upstream provider. {@code login} becomes the
 * {@code SecurityIdentity} principal. Produced by the application's
 * {@link eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver}.
 */
public record UpstreamUser(long id, String login, String email) {}
