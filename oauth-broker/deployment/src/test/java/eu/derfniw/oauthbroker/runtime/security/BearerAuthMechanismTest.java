package eu.derfniw.oauthbroker.runtime.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.crypto.TokenCrypto;
import eu.derfniw.oauthbroker.runtime.crypto.TokenType;
import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BearerAuthMechanismTest {

    @Inject
    TokenCrypto tokenCrypto;

    private String mintAccessToken(Instant expiresAt) {
        AccessTokenEntry entry = new AccessTokenEntry(
                "https://client.example/cimd.json",
                List.of("read:repository", "read:issue"),
                new UpstreamTokens("upstream-bearer", "up-rt", Instant.now().plusSeconds(3600)),
                new UpstreamUser(7L, "tester", "tester@example.com"),
                expiresAt);
        return tokenCrypto.encode(TokenType.ACCESS_TOKEN, entry);
    }

    @Test
    void missingAuthorizationHeaderReturns401WithChallenge() {
        given().when()
                .get("/mcp/_probe/whoami")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"))
                .header("WWW-Authenticate", containsString("resource_metadata="));
    }

    @Test
    void nonBearerSchemeReturns401() {
        given().header("Authorization", "Basic dXNlcjpwYXNz")
                .when()
                .get("/mcp/_probe/whoami")
                .then()
                .statusCode(401);
    }

    @Test
    void garbageBearerReturns401() {
        given().header("Authorization", "Bearer mcp_at_not-a-real-envelope")
                .when()
                .get("/mcp/_probe/whoami")
                .then()
                .statusCode(401);
    }

    @Test
    void wrongPrefixBearerReturns401() {
        String refreshShaped = mintAccessToken(Instant.now().plusSeconds(300));
        String tampered = refreshShaped.replaceFirst("mcp_at_", "mcp_rt_");
        given().header("Authorization", "Bearer " + tampered)
                .when()
                .get("/mcp/_probe/whoami")
                .then()
                .statusCode(401);
    }

    @Test
    void expiredBearerReturns401() {
        String token = mintAccessToken(Instant.now().minusSeconds(10));
        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/mcp/_probe/whoami")
                .then()
                .statusCode(401);
    }

    @Test
    void freshBearerReachesProbeAndCarriesUpstreamIdentity() {
        String token = mintAccessToken(Instant.now().plusSeconds(300));
        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/mcp/_probe/whoami")
                .then()
                .statusCode(200)
                .body("principal", equalTo("tester"))
                .body("login", equalTo("tester"))
                .body("userId", equalTo(7))
                .body("email", equalTo("tester@example.com"))
                .body("upstreamBearer", equalTo("upstream-bearer"))
                .body("scope", hasItem("read:repository"))
                .body("scope", hasItem("read:issue"));
    }

    @Test
    void nonMcpPathsRemainPublic() {
        given().when().get("/.well-known/oauth-authorization-server").then().statusCode(200);
    }
}
