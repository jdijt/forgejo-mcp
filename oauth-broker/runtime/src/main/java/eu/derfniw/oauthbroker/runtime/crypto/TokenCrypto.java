package eu.derfniw.oauthbroker.runtime.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.derfniw.oauthbroker.runtime.config.BrokerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMSIVBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Symmetric AEAD ({@code AES-256-GCM-SIV}, BouncyCastle lightweight API) for the broker's opaque
 * tokens. Each token is encoded as {@code <prefix><base64url(nonce || ciphertext+tag)>}.
 * The prefix is bound as additional authenticated data so tokens of one kind can't be decrypted as another even
 * if they share the same key. Expiry is enforced post-decrypt for payloads implementing {@link Expirable}.
 *
 * <p>Each {@code encode} draws a fresh 96-bit {@link SecureRandom} nonce. GCM-SIV is nonce-misuse
 * resistant (RFC 8452): a repeated {@code (key, nonce)} leaks only plaintext equality.
 *
 * <p>We use BouncyCastle's lightweight {@link GCMSIVBlockCipher} directly rather than via a
 * registered JCE provider, which keeps native-image builds free of provider-registration and
 * reflection config.
 */
@ApplicationScoped
public class TokenCrypto {

    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int KEY_BYTES = 32;

    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final KeyParameter key;
    private final ObjectMapper json;
    private final SecureRandom rng = new SecureRandom();

    public TokenCrypto(BrokerConfig broker, ObjectMapper json) {
        byte[] keyBytes;
        try {
            var regularB64Dec = Base64.getDecoder();
            keyBytes = regularB64Dec.decode(broker.tokenEncryptionKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("broker.token-encryption-key is not valid base64url", e);
        }
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "broker.token-encryption-key must decode to " + KEY_BYTES + " bytes, was " + keyBytes.length);
        }
        this.key = new KeyParameter(keyBytes);
        this.json = json;
    }

    public <T> String encode(TokenType tType, T payload) {
        String prefix = tType.getPrefix();
        try {
            byte[] plaintext = json.writeValueAsBytes(payload);
            byte[] nonce = generateNonce();
            byte[] ciphertext = cipher(true, nonce, prefix, plaintext);
            byte[] envelope = new byte[NONCE_LEN + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, NONCE_LEN);
            System.arraycopy(ciphertext, 0, envelope, NONCE_LEN, ciphertext.length);
            return prefix + B64_ENC.encodeToString(envelope);
        } catch (IOException | InvalidCipherTextException e) {
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
            throw new TokenCryptoException("token body invalid");
        }
        byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_LEN);
        byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_LEN, envelope.length);

        byte[] plaintext;
        try {
            plaintext = cipher(false, nonce, prefix, ciphertext);
        } catch (InvalidCipherTextException e) {
            throw new TokenCryptoException("token authentication failed.", e);
        }

        T payload;
        try {
            payload = json.readValue(plaintext, type);
        } catch (IOException e) {
            throw new TokenCryptoException("token payload is not valid JSON for " + type.getSimpleName(), e);
        }

        if (payload instanceof Expirable expirable && expirable.isExpired()) {
            throw new TokenCryptoException("token expired");
        }
        return payload;
    }

    private byte[] generateNonce() {
        byte[] nonce = new byte[NONCE_LEN];
        rng.nextBytes(nonce);
        return nonce;
    }

    /**
     * Run AES-256-GCM-SIV in one shot over {@code input} with {@code prefix} bound as AAD.
     */
    private byte[] cipher(boolean encrypt, byte[] nonce, String prefix, byte[] input)
            throws InvalidCipherTextException {
        GCMSIVBlockCipher c = new GCMSIVBlockCipher(AESEngine.newInstance());
        byte[] aad = prefix.getBytes(StandardCharsets.UTF_8);
        c.init(encrypt, new AEADParameters(key, TAG_BITS, nonce, aad));
        byte[] out = new byte[c.getOutputSize(input.length)];
        int len = c.processBytes(input, 0, input.length, out, 0);
        len += c.doFinal(out, len);
        return len == out.length ? out : Arrays.copyOf(out, len);
    }
}
