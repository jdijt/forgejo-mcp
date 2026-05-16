package eu.derfniw.mcp.forgejo.testsupport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Spins up a real Forgejo instance, bootstraps an admin + a regular test user, registers the broker
 * as a confidential OAuth2 application, and exposes the resulting credentials as Quarkus config
 * overrides.
 * <p>
 * Static accessors expose fixture details (test user creds, base URL) for tests that need to drive
 * the OAuth flow as the user.
 */
public class ForgejoTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String IMAGE = "codeberg.org/forgejo/forgejo:15";
    private static final int FORGEJO_PORT = 3000;

    public static final String ADMIN_USERNAME = "broker-admin";
    public static final String ADMIN_PASSWORD = "Admin-Password-1!";
    public static final String ADMIN_EMAIL = "admin@test.local";

    public static final String TEST_USERNAME = "tester";
    public static final String TEST_PASSWORD = "Tester-Password-1!";
    public static final String TEST_EMAIL = "tester@test.local";

    public static final String BROKER_REDIRECT_URI = "http://localhost:8081/oauth/callback";
    public static final String OAUTH_APP_NAME = "Forgejo MCP Broker (test)";

    private static volatile String baseUrl;
    private static volatile String oauthClientId;
    private static volatile String oauthClientSecret;

    private GenericContainer<?> forgejo;

    @Override
    public Map<String, String> start() {
        forgejo = new GenericContainer<>(IMAGE)
                .withExposedPorts(FORGEJO_PORT)
                .withEnv("USER_UID", "1000")
                .withEnv("USER_GID", "1000")
                .withEnv("FORGEJO__security__INSTALL_LOCK", "true")
                .withEnv("FORGEJO__service__DISABLE_REGISTRATION", "true")
                .waitingFor(Wait.forHttp("/api/v1/version")
                        .forPort(FORGEJO_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        forgejo.start();

        baseUrl = "http://localhost:" + forgejo.getMappedPort(FORGEJO_PORT);

        try {
            createUser(ADMIN_USERNAME, ADMIN_PASSWORD, ADMIN_EMAIL, true);
            createUser(TEST_USERNAME, TEST_PASSWORD, TEST_EMAIL, false);
            registerOAuthApp();
        } catch (Exception e) {
            throw new RuntimeException("Forgejo bootstrap failed", e);
        }

        return Map.of(
                "forgejo.base-url", baseUrl,
                "forgejo.oauth.client-id", oauthClientId,
                "forgejo.oauth.client-secret", oauthClientSecret);
    }

    @Override
    public void stop() {
        if (forgejo != null) {
            forgejo.stop();
        }
    }

    private void createUser(String username, String password, String email, boolean admin) throws Exception {
        // The Forgejo binary in the official container lives at /usr/local/bin/forgejo and
        // must be invoked as the `git` user so it can read its data directory.
        String[] baseCommand = new String[] {
            "forgejo",
            "admin",
            "user",
            "create",
            "--username",
            username,
            "--password",
            password,
            "--email",
            email,
            "--must-change-password=false"
        };
        String[] adminCommand = new String[] {
            "forgejo",
            "admin",
            "user",
            "create",
            "--admin",
            "--username",
            username,
            "--password",
            password,
            "--email",
            email,
            "--must-change-password=false"
        };

        String[] cmd = admin ? adminCommand : baseCommand;

        Container.ExecResult result = forgejo.execInContainer(org.testcontainers.containers.ExecConfig.builder()
                .user("git")
                .command(cmd)
                .build());

        if (result.getExitCode() != 0) {
            throw new RuntimeException("forgejo admin user create failed (exit " + result.getExitCode() + ")"
                    + "\nstdout: " + result.getStdout()
                    + "\nstderr: " + result.getStderr());
        }
    }

    private record CreateOAuthAppRequest(String name, List<String> redirect_uris, boolean confidential_client) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateOAuthAppResponse(String client_id, String client_secret) {}

    private void registerOAuthApp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] body =
                mapper.writeValueAsBytes(new CreateOAuthAppRequest(OAUTH_APP_NAME, List.of(BROKER_REDIRECT_URI), true));
        String basicAuth = Base64.getEncoder()
                .encodeToString((ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/user/applications/oauth2"))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try (HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException(
                        "OAuth app create failed: HTTP " + resp.statusCode() + " body=" + resp.body());
            }
            CreateOAuthAppResponse parsed = mapper.readValue(resp.body(), CreateOAuthAppResponse.class);
            oauthClientId = parsed.client_id();
            oauthClientSecret = parsed.client_secret();
        }
    }

    public static String baseUrl() {
        return baseUrl;
    }

    public static String oauthClientId() {
        return oauthClientId;
    }

    public static String oauthClientSecret() {
        return oauthClientSecret;
    }
}
