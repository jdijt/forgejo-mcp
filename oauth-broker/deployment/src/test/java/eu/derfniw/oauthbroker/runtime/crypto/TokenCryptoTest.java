package eu.derfniw.oauthbroker.runtime.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.derfniw.oauthbroker.runtime.envelope.PendingAuth;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TokenCryptoTest {

    @Inject
    TokenCrypto crypto;

    private PendingAuth samplePending(Instant expiresAt) {
        return new PendingAuth(
                "https://client.example/.well-known/oauth-client",
                URI.create("https://client.example/cb"),
                "claude-state",
                "abc123",
                "S256",
                List.of("read:repository"),
                expiresAt);
    }

    @Test
    void roundTrip() {
        PendingAuth in = samplePending(Instant.now().plusSeconds(60));
        String token = crypto.encode(TokenType.PENDING_AUTH, in);
        assertTrue(token.startsWith(TokenType.PENDING_AUTH.getPrefix()), "token must carry prefix");
        PendingAuth out = crypto.decode(TokenType.PENDING_AUTH, token, PendingAuth.class);
        assertEquals(in, out);
    }

    @Test
    void encodeProducesDistinctTokensForSameInput() {
        PendingAuth in = samplePending(Instant.now().plusSeconds(60));
        String a = crypto.encode(TokenType.PENDING_AUTH, in);
        String b = crypto.encode(TokenType.PENDING_AUTH, in);
        assertNotEquals(a, b, "nonce randomisation must yield different ciphertexts");
    }

    @Test
    void prefixMismatchIsRejected() {
        String token = crypto.encode(
                TokenType.PENDING_AUTH, samplePending(Instant.now().plusSeconds(60)));
        TokenCryptoException e = assertThrows(
                TokenCryptoException.class, () -> crypto.decode(TokenType.ACCESS_TOKEN, token, PendingAuth.class));
        assertTrue(e.getMessage().contains("prefix"), e.getMessage());
    }

    @Test
    void tokensEncodedUnderDifferentPrefixDoNotCrossDecode() {
        // Same payload type, encoded under PA_PREFIX, must not decode when caller claims AC_PREFIX —
        // and vice versa. AAD-binding of the prefix means decryption itself fails.
        String token = crypto.encode(
                TokenType.PENDING_AUTH, samplePending(Instant.now().plusSeconds(60)));
        String swapped = TokenType.ACCESS_TOKEN.getPrefix()
                + token.substring(TokenType.PENDING_AUTH.getPrefix().length());
        assertThrows(
                TokenCryptoException.class, () -> crypto.decode(TokenType.ACCESS_TOKEN, swapped, PendingAuth.class));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String token = crypto.encode(
                TokenType.PENDING_AUTH, samplePending(Instant.now().plusSeconds(60)));
        // Flip a character somewhere in the body (not prefix). Base64url alphabet — swap 'A'<->'B'.
        char[] chars = token.toCharArray();
        int idx = TokenType.PENDING_AUTH.getPrefix().length() + 5;
        chars[idx] = (chars[idx] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);
        assertThrows(
                TokenCryptoException.class, () -> crypto.decode(TokenType.PENDING_AUTH, tampered, PendingAuth.class));
    }

    @Test
    void expiredTokenIsRejected() {
        PendingAuth in = samplePending(Instant.now().minusSeconds(1));
        String token = crypto.encode(TokenType.PENDING_AUTH, in);
        TokenCryptoException e = assertThrows(
                TokenCryptoException.class, () -> crypto.decode(TokenType.PENDING_AUTH, token, PendingAuth.class));
        assertTrue(e.getMessage().contains("expired"), e.getMessage());
    }

    @Test
    void garbageBodyIsRejected() {
        assertThrows(
                TokenCryptoException.class,
                () -> crypto.decode(
                        TokenType.PENDING_AUTH, TokenType.PENDING_AUTH + "!!not-base64!!", PendingAuth.class));
    }
}
