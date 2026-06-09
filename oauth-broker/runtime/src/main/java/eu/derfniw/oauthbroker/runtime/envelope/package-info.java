/**
 * Plaintext payloads of the broker's AES-GCM envelope tokens (access/refresh/auth-code) and the
 * in-flight authorize state. Internal: these are encrypted by {@code crypto.TokenCrypto} and never
 * cross the wire as JSON.
 */
@NullMarked
package eu.derfniw.oauthbroker.runtime.envelope;

import org.jspecify.annotations.NullMarked;
