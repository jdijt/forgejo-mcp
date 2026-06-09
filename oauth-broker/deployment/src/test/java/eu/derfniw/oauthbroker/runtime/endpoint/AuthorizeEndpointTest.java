package eu.derfniw.oauthbroker.runtime.endpoint;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthorizeEndpointTest {

    private static final String CLIENT_REDIRECT = "https://client.example/oauth/callback";
    private static final String VALID_CHALLENGE = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    private HttpServer cimdServer;
    private String clientIdUrl;

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    @BeforeEach
    void startCimd() throws IOException {
        cimdServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = cimdServer.getAddress().getPort();
        clientIdUrl = "http://127.0.0.1:" + port + "/cimd.json";
        String body = """
                {
                  "client_id": "%s",
                  "client_name": "Test Client",
                  "redirect_uris": ["%s"]
                }
                """.formatted(clientIdUrl, CLIENT_REDIRECT);
        cimdServer.createContext("/cimd.json", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        cimdServer.start();
    }

    @AfterEach
    void stopCimd() {
        if (cimdServer != null) cimdServer.stop(0);
    }

    @Test
    void happyPathBouncesToUpstreamWithExpectedParams() {
        Response resp = given().redirects()
                .follow(false)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientIdUrl)
                .queryParam("redirect_uri", CLIENT_REDIRECT)
                .queryParam("scope", "read:repository read:issue")
                .queryParam("state", "claude-state-123")
                .queryParam("code_challenge", VALID_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/authorize");

        assertEquals(302, resp.statusCode());
        String location = resp.getHeader("Location");
        assertNotNull(location);
        URI loc = URI.create(location);
        assertEquals("upstream.test", loc.getHost());
        assertEquals("/login/oauth/authorize", loc.getPath());

        Map<String, String> params = parseQuery(loc.getRawQuery());
        assertEquals("code", params.get("response_type"));
        assertEquals("test-client-id", params.get("client_id"));
        assertEquals("http://localhost:8081/oauth/callback", params.get("redirect_uri"));
        // The broker always requests the full configured upstream scope set (which includes
        // whatever the user-info resolver needs), independent of what the client requested.
        assertEquals("read:repository read:issue read:user", params.get("scope"));
        assertNotNull(params.get("state"), "upstream-side state must be present");
        assertTrue(params.get("state").length() > 16, "state should be a random id");
    }

    @Test
    void missingClientIdYields400() {
        given().redirects()
                .follow(false)
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", CLIENT_REDIRECT)
                .queryParam("code_challenge", VALID_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/authorize")
                .then()
                .statusCode(400);
    }

    @Test
    void redirectUriNotInCimdYields400() {
        given().redirects()
                .follow(false)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientIdUrl)
                .queryParam("redirect_uri", "https://attacker.example/cb")
                .queryParam("code_challenge", VALID_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/authorize")
                .then()
                .statusCode(400);
    }

    @Test
    void wrongResponseTypeRedirectsWithError() {
        Response resp = given().redirects()
                .follow(false)
                .queryParam("response_type", "token")
                .queryParam("client_id", clientIdUrl)
                .queryParam("redirect_uri", CLIENT_REDIRECT)
                .queryParam("state", "s")
                .queryParam("code_challenge", VALID_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/authorize");

        assertEquals(302, resp.statusCode());
        URI loc = URI.create(resp.getHeader("Location"));
        Map<String, String> params = parseQuery(loc.getRawQuery());
        assertEquals("unsupported_response_type", params.get("error"));
        assertEquals("s", params.get("state"));
    }

    @Test
    void missingPkceRedirectsWithError() {
        Response resp = given().redirects()
                .follow(false)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientIdUrl)
                .queryParam("redirect_uri", CLIENT_REDIRECT)
                .queryParam("state", "s")
                .when()
                .get("/authorize");

        assertEquals(302, resp.statusCode());
        URI loc = URI.create(resp.getHeader("Location"));
        Map<String, String> params = parseQuery(loc.getRawQuery());
        assertEquals("invalid_request", params.get("error"));
    }

    @Test
    void plainPkceMethodRedirectsWithError() {
        Response resp = given().redirects()
                .follow(false)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientIdUrl)
                .queryParam("redirect_uri", CLIENT_REDIRECT)
                .queryParam("code_challenge", VALID_CHALLENGE)
                .queryParam("code_challenge_method", "plain")
                .when()
                .get("/authorize");

        assertEquals(302, resp.statusCode());
        URI loc = URI.create(resp.getHeader("Location"));
        Map<String, String> params = parseQuery(loc.getRawQuery());
        assertEquals("invalid_request", params.get("error"));
    }

    @Test
    void scopeBeyondAdvertisedRedirectsWithError() {
        Response resp = given().redirects()
                .follow(false)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientIdUrl)
                .queryParam("redirect_uri", CLIENT_REDIRECT)
                .queryParam("scope", "write:repository")
                .queryParam("code_challenge", VALID_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/authorize");

        assertEquals(302, resp.statusCode());
        URI loc = URI.create(resp.getHeader("Location"));
        Map<String, String> params = parseQuery(loc.getRawQuery());
        assertEquals("invalid_scope", params.get("error"));
    }
}
