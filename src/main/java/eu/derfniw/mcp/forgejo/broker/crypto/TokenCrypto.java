package eu.derfniw.mcp.forgejo.broker.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.derfniw.mcp.forgejo.broker.model.TokenCryptoException;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Symmetric AEAD ({@code AES-256-GCM}) for the broker's opaque tokens. Each token is encoded as
 * {@code <prefix><base64url(nonce || ciphertext+tag)>}. The prefix is bound as additional
 * authenticated data so tokens of one kind can't be decrypted as another even if they share the
 * same key. Expiry is enforced post-decrypt for payloads implementing {@link Expirable}.
 */
@ApplicationScoped
public class TokenCrypto {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int KEY_BYTES = 32;

    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final ObjectMapper json;
    private final SecureRandom rng = new SecureRandom();

    public TokenCrypto(BrokerConfig broker, ObjectMapper json) {
        byte[] keyBytes;
        try {
            keyBytes = B64_DEC.decode(broker.tokenEncryptionKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("broker.token-encryption-key is not valid base64url", e);
        }
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "broker.token-encryption-key must decode to " + KEY_BYTES + " bytes, was " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
        this.json = json;
    }

    public <T> String encode(TokenType tType, T payload) {
        String prefix = tType.getPrefix();
        try {
            byte[] plaintext = json.writeValueAsBytes(payload);
            byte[] nonce = new byte[NONCE_LEN];
            rng.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] envelope = new byte[NONCE_LEN + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, NONCE_LEN);
            System.arraycopy(ciphertext, 0, envelope, NONCE_LEN, ciphertext.length);
            return prefix + B64_ENC.encodeToString(envelope);
        } catch (GeneralSecurityException | IOException e) {
            throw new TokenCryptoException("token encode failed", e);
        }
    }

    public <T> T decode(TokenType tType, String token, Class<T> type) {
        String prefix = tType.getPrefix();
        if (!token.startsWith(prefix)) {
            throw new TokenCryptoException("token does not start with expected prefix '" + prefix + "'");
        }
        byte[] envelope;
        try {
            envelope = B64_DEC.decode(token.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            throw new TokenCryptoException("token body is not valid base64url", e);
        }
        if (envelope.length < NONCE_LEN + TAG_BYTES) {
            throw new TokenCryptoException("token body shorter than nonce+tag");
        }
        byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_LEN);
        byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_LEN, envelope.length);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new TokenCryptoException("token authentication failed (tampered, wrong key, or wrong prefix)", e);
        }

        T payload;
        try {
            payload = json.readValue(plaintext, type);
        } catch (IOException e) {
            throw new TokenCryptoException("token payload is not valid JSON for " + type.getSimpleName(), e);
        }

        if (payload instanceof Expirable expirable && Instant.now().isAfter(expirable.expiresAt())) {
            throw new TokenCryptoException("token expired at " + expirable.expiresAt());
        }
        return payload;
    }
}
