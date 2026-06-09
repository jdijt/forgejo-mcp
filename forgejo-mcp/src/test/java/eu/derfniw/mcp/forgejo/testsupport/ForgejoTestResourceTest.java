package eu.derfniw.mcp.forgejo.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.derfniw.oauthbroker.runtime.config.BrokerConfig;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(ForgejoTestResource.class)
class ForgejoTestResourceTest {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    BrokerConfig broker;

    @Test
    void upstreamConfigPicksUpOverridesFromTestResource() {
        // The test resource overrides forgejo.base-url + forgejo.oauth.* which the app interpolates
        // into broker.upstream.*; assert those resolved values point at the container's OAuth app.
        assertEquals(
                URI.create(ForgejoTestResource.baseUrl() + "/login/oauth/authorize"),
                broker.upstream().authorizeUrl(),
                "authorize-url should derive from the container's base URL");
        assertEquals(
                URI.create(ForgejoTestResource.baseUrl() + "/login/oauth/access_token"),
                broker.upstream().tokenUrl(),
                "token-url should derive from the container's base URL");
        assertEquals(ForgejoTestResource.oauthClientId(), broker.upstream().clientId());
        assertEquals(ForgejoTestResource.oauthClientSecret(), broker.upstream().clientSecret());
        assertNotNull(broker.upstream().clientId());
        assertTrue(broker.upstream().clientId().length() > 8, "Forgejo issues UUID-shaped client ids");
    }

    @Test
    void forgejoApiIsReachable() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(ForgejoTestResource.baseUrl() + "/api/v1/version"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), () -> "body=" + resp.body());
        JsonNode json = JSON.readTree(resp.body());
        assertNotNull(json.get("version"), "version field present");
    }

    @Test
    void testUserCanAuthenticateAgainstForgejoApi() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((ForgejoTestResource.TEST_USERNAME + ":" + ForgejoTestResource.TEST_PASSWORD)
                        .getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(ForgejoTestResource.baseUrl() + "/api/v1/user"))
                        .header("Authorization", "Basic " + basic)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), () -> "body=" + resp.body());
        JsonNode user = JSON.readTree(resp.body());
        assertEquals(ForgejoTestResource.TEST_USERNAME, user.get("login").asText());
    }

    @Test
    void registeredOAuthAppIsListedForAdmin() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((ForgejoTestResource.ADMIN_USERNAME + ":" + ForgejoTestResource.ADMIN_PASSWORD)
                        .getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(ForgejoTestResource.baseUrl() + "/api/v1/user/applications/oauth2"))
                        .header("Authorization", "Basic " + basic)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), () -> "body=" + resp.body());
        JsonNode apps = JSON.readTree(resp.body());
        assertTrue(apps.isArray() && apps.size() >= 1, "at least one OAuth app should be registered");
        boolean found = false;
        for (JsonNode app : apps) {
            if (ForgejoTestResource.OAUTH_APP_NAME.equals(app.get("name").asText())) {
                found = true;
                JsonNode redirects = app.get("redirect_uris");
                assertEquals(
                        ForgejoTestResource.BROKER_REDIRECT_URI,
                        redirects.get(0).asText());
                assertTrue(app.get("confidential_client").asBoolean(), "must be confidential");
            }
        }
        assertTrue(found, "broker OAuth app should be among the registered apps");
    }
}
