package eu.derfniw.mcp.forgejo.forgejo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import eu.derfniw.mcp.forgejo.testsupport.ForgejoTestResource;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end happy path for the full OAuth dance against a real Forgejo container.
 *
 * <p>Plain HTTP covers the broker's server-to-server hops (/authorize, /token, /mcp/*), while
 * Playwright drives the only steps that involve a user: the Forgejo login form and the OAuth
 * consent screen. This avoids brittle CSRF scraping and manual redirect chasing.
 *
 * <p>Picks up the deferred {@code /oauth/callback} happy-path and refresh-grant happy-path from
 * Phase 1.3 / 1.4.
 */
@QuarkusTest
@WithTestResource(ForgejoTestResource.class)
@WithPlaywright
class EndToEndOAuthFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @InjectPlaywright
    Browser browser;

    private HttpServer cimdServer;
    private String clientIdUrl;
    private String clientRedirect;

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String formEncode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> url(e.getKey()) + "=" + url(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
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
        clientRedirect = "http://127.0.0.1:" + port + "/client/callback";
        String body = """
                {
                  "client_id": "%s",
                  "client_name": "E2E Test Client",
                  "redirect_uris": ["%s"]
                }
                """.formatted(clientIdUrl, clientRedirect);
        cimdServer.createContext("/cimd.json", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        // The client redirect_uri itself only needs to exist (so Playwright can navigate to it
        // and we can read the URL); an empty 200 is fine.
        cimdServer.createContext("/client/callback", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        cimdServer.start();
    }

    @AfterEach
    void stopCimd() {
        if (cimdServer != null) cimdServer.stop(0);
    }

    @Test
    void fullOAuthDanceResolvesToForgejoTestUserAndRefreshWorks() throws Exception {
        String verifier = "the-quick-brown-fox-jumps-over-the-lazy-dog-1234567890";
        String challenge = s256(verifier);
        String mcpState = "client-state-abc";

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Plain HTTP: broker /authorize → 302 to Forgejo authorize.
        Map<String, String> authParams = new LinkedHashMap<>();
        authParams.put("response_type", "code");
        authParams.put("client_id", clientIdUrl);
        authParams.put("redirect_uri", clientRedirect);
        authParams.put("scope", "read:repository read:issue");
        authParams.put("state", mcpState);
        authParams.put("code_challenge", challenge);
        authParams.put("code_challenge_method", "S256");
        URI brokerAuthorize = URI.create("http://localhost:8081/authorize?" + formEncode(authParams));
        HttpResponse<String> authResp =
                http.send(HttpRequest.newBuilder(brokerAuthorize).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, authResp.statusCode(), () -> "body=" + authResp.body());
        String forgejoAuthorize = authResp.headers().firstValue("Location").orElseThrow();

        // 2. Playwright: drive Forgejo's login + OAuth consent UI. Playwright follows redirects,
        //    handles cookies + CSRF transparently, and finally lands on our client_redirect URL
        //    (or the broker callback if something goes wrong before the redirect home).
        String clientLandingUrl;
        try (BrowserContext ctx = browser.newContext();
                Page page = ctx.newPage()) {
            page.navigate(forgejoAuthorize);
            // Forgejo's unauthenticated /login/oauth/authorize bounces to /user/login.
            page.waitForURL("**/user/login**");
            page.fill("input[name=user_name]", ForgejoTestResource.TEST_USERNAME);
            page.fill("input[name=password]", ForgejoTestResource.TEST_PASSWORD);
            // Forgejo's "Sign In" button has no explicit type=submit; pressing Enter on the
            // password input is the most robust way to submit the form across Forgejo themes.
            page.press("input[name=password]", "Enter");

            // After login, Forgejo re-renders the OAuth grant screen with two submit buttons named
            // "granted" (values "true" / "false"). Click "Authorize" (value=true).
            page.waitForURL("**/login/oauth/authorize**");
            page.locator("button[name=granted][value=true]").click();

            // The grant POST 302s to the broker callback, which 302s to client_redirect — Playwright
            // follows both and lands on our local CIMD server's /client/callback (empty 200).
            page.waitForURL("http://127.0.0.1:*/client/callback**");
            clientLandingUrl = page.url();
        }

        Map<String, String> clientParams =
                parseQuery(URI.create(clientLandingUrl).getRawQuery());
        String brokerCode = clientParams.get("code");
        assertNotNull(brokerCode, () -> "client callback missing code: " + clientLandingUrl);
        assertTrue(brokerCode.startsWith("mcp_ac_"));
        assertEquals(mcpState, clientParams.get("state"), "broker should echo the client's original state");

        // 3. Plain HTTP: exchange the broker code at /token.
        Map<String, String> tokenForm = new LinkedHashMap<>();
        tokenForm.put("grant_type", "authorization_code");
        tokenForm.put("code", brokerCode);
        tokenForm.put("redirect_uri", clientRedirect);
        tokenForm.put("client_id", clientIdUrl);
        tokenForm.put("code_verifier", verifier);
        HttpResponse<String> tokenResp = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:8081/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formEncode(tokenForm)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, tokenResp.statusCode(), () -> "token body=" + tokenResp.body());
        JsonNode tok = JSON.readTree(tokenResp.body());
        String accessToken = tok.get("access_token").asText();
        String refreshToken = tok.get("refresh_token").asText();
        assertTrue(accessToken.startsWith("mcp_at_"));
        assertTrue(refreshToken.startsWith("mcp_rt_"));
        assertEquals("read:repository read:issue", tok.get("scope").asText());

        // 4. Plain HTTP: probe MCP endpoint with the bearer; it must resolve to the Forgejo test user.
        HttpResponse<String> probe = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:8081/mcp/_probe/whoami"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, probe.statusCode(), () -> "probe body=" + probe.body());
        JsonNode who = JSON.readTree(probe.body());
        assertEquals(ForgejoTestResource.TEST_USERNAME, who.get("login").asText());
        assertEquals(ForgejoTestResource.TEST_EMAIL, who.get("email").asText());
        assertTrue(
                who.get("upstreamBearer").asText().length() > 8,
                "probe should carry the embedded upstream bearer through SecurityIdentity");

        // 5. Refresh-grant happy path — exercises the real upstream refresh that
        //    TokenEndpointTest could not reach without a real Forgejo refresh token.
        Map<String, String> refreshForm = new LinkedHashMap<>();
        refreshForm.put("grant_type", "refresh_token");
        refreshForm.put("refresh_token", refreshToken);
        refreshForm.put("client_id", clientIdUrl);
        HttpResponse<String> refreshResp = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:8081/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formEncode(refreshForm)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, refreshResp.statusCode(), () -> "refresh body=" + refreshResp.body());
        JsonNode refreshed = JSON.readTree(refreshResp.body());
        assertTrue(refreshed.get("access_token").asText().startsWith("mcp_at_"));
        assertTrue(refreshed.get("refresh_token").asText().startsWith("mcp_rt_"));
    }
}
