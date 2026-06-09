package eu.derfniw.oauthbroker.runtime.crypto;

/**
 * Technical failure of {@link TokenCrypto} encode/decode: bad key, wrong prefix, failed AEAD
 * authentication (tampered/expired), or malformed payload JSON. This is <em>not</em> a domain
 * {@code BrokerException} — callers translate it at the boundary into whatever the surface requires
 * (e.g. a 401 challenge on {@code /mcp/*}, {@code invalid_grant} on {@code /token}, or a
 * {@code BadRequest} for an undecryptable {@code state} on the callback).
 */
public final class TokenCryptoException extends RuntimeException {

    public TokenCryptoException(String message) {
        super(message);
    }

    public TokenCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
