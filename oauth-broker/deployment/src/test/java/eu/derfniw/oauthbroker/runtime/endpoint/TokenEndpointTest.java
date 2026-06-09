package eu.derfniw.oauthbroker.runtime.endpoint;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.crypto.TokenCrypto;
import eu.derfniw.oauthbroker.runtime.crypto.TokenType;
import eu.derfniw.oauthbroker.runtime.envelope.AuthCodeEntry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TokenEndpointTest {

    private static final String CLIENT_ID = "https://client.example/cimd.json";
    private static final String REDIRECT_URI = "https://client.example/oauth/callback";
    private static final String VERIFIER = "the-quick-brown-fox-jumps-over-the-lazy-dog-1234567890";
    private static final String CHALLENGE = s256(VERIFIER);

    @Inject
    TokenCrypto tokenCrypto;

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AuthCodeEntry sampleEntry() {
        return new AuthCodeEntry(
                CLIENT_ID,
                URI.create(REDIRECT_URI),
                CHALLENGE,
                "S256",
                List.of("read:repository", "read:issue"),
                new UpstreamTokens("up-at", "up-rt", Instant.now().plusSeconds(3600)),
                new UpstreamUser(42L, "tester", "tester@example.com"),
                Instant.now().plusSeconds(60));
    }

    private String encodeAuthCode(AuthCodeEntry entry) {
        return tokenCrypto.encode(TokenType.AUTH_CODE, entry);
    }

    @Test
    void happyPathReturnsAccessAndRefreshTokens() {
        String code = encodeAuthCode(sampleEntry());

        given().formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", REDIRECT_URI)
                .formParam("client_id", CLIENT_ID)
                .formParam("code_verifier", VERIFIER)
                .when()
                .post("/token")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("access_token", startsWith("mcp_at_"))
                .body("refresh_token", startsWith("mcp_rt_"))
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", notNullValue())
                .body("scope", equalTo("read:repository read:issue"));
    }

    @Test
    void missingGrantTypeReturnsInvalidRequest() {
        given().formParam("code", "anything")
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    void unknownGrantTypeReturnsUnsupportedGrantType() {
        given().formParam("grant_type", "password")
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_grant_type"));
    }

    @Test
    void garbageCodeReturnsInvalidGrant() {
        given().formParam("grant_type", "authorization_code")
                .formParam("code", "mcp_ac_not-a-real-envelope")
                .formParam("redirect_uri", REDIRECT_URI)
                .formParam("client_id", CLIENT_ID)
                .formParam("code_verifier", VERIFIER)
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void wrongClientIdReturnsInvalidGrant() {
        String code = encodeAuthCode(sampleEntry());

        given().formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", REDIRECT_URI)
                .formParam("client_id", "https://other.example/cimd.json")
                .formParam("code_verifier", VERIFIER)
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void wrongRedirectUriReturnsInvalidGrant() {
        String code = encodeAuthCode(sampleEntry());

        given().formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", "https://client.example/other")
                .formParam("client_id", CLIENT_ID)
                .formParam("code_verifier", VERIFIER)
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void wrongCodeVerifierReturnsInvalidGrant() {
        String code = encodeAuthCode(sampleEntry());

        given().formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", REDIRECT_URI)
                .formParam("client_id", CLIENT_ID)
                .formParam("code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier")
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void expiredCodeReturnsInvalidGrant() {
        AuthCodeEntry expired = new AuthCodeEntry(
                CLIENT_ID,
                URI.create(REDIRECT_URI),
                CHALLENGE,
                "S256",
                List.of("read:repository"),
                new UpstreamTokens("up-at", "up-rt", Instant.now().plusSeconds(3600)),
                new UpstreamUser(1L, "x", "x@example.com"),
                Instant.now().minusSeconds(10));
        String code = encodeAuthCode(expired);

        given().formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", REDIRECT_URI)
                .formParam("client_id", CLIENT_ID)
                .formParam("code_verifier", VERIFIER)
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void refreshGrantMissingRefreshTokenReturnsInvalidRequest() {
        given().formParam("grant_type", "refresh_token")
                .formParam("client_id", CLIENT_ID)
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    void refreshGrantGarbageTokenReturnsInvalidGrant() {
        given().formParam("grant_type", "refresh_token")
                .formParam("refresh_token", "mcp_rt_garbage")
                .formParam("client_id", CLIENT_ID)
                .when()
                .post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }
}
