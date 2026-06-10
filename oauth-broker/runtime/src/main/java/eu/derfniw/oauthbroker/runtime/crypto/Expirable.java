package eu.derfniw.oauthbroker.runtime.crypto;

import java.time.Instant;

/** Marker for envelope records carrying their own expiry. Checked on decrypt. */
public interface Expirable {
    Instant expiresAt();

    default boolean isExpired() {
        return Instant.now().isAfter(expiresAt());
    }
}
