package eu.derfniw.oauthbroker.runtime.api;

import java.time.Instant;

/** OAuth tokens issued by the upstream provider, embedded in the broker's envelope tokens. */
public record UpstreamTokens(String accessToken, String refreshToken, Instant accessExpiresAt) {}
