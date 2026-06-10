package eu.derfniw.oauthbroker.runtime.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.config.BrokerConfig;
import eu.derfniw.oauthbroker.runtime.error.UpstreamFailure;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Generic OAuth 2.0 client for the upstream provider. The authorization-code exchange and refresh
 * are standardized, so this drives them purely from {@link BrokerConfig.Upstream} config via a plain
 * {@code application/x-www-form-urlencoded} POST to the configured token endpoint
 * ({@code client_secret_post} authentication). The one non-standard concern — resolving the user
 * identity — is delegated to the application's
 * {@link eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver}.
 */
@ApplicationScoped
public class UpstreamOAuthClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final BrokerConfig broker;
    private final ObjectMapper json;
    private final HttpClient http;

    public UpstreamOAuthClient(BrokerConfig broker, ObjectMapper objectMapper) {
        this.broker = broker;
        this.json = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    public UpstreamTokens exchangeCode(String code, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        return post(form, "token exchange");
    }

    public UpstreamTokens refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return post(form, "token refresh");
    }

    private UpstreamTokens post(Map<String, String> form, String what) {
        BrokerConfig.Upstream up = broker.upstream();
        form.put("client_id", up.clientId());
        form.put("client_secret", up.clientSecret());

        HttpRequest req = HttpRequest.newBuilder(URI.create(up.tokenUrl().toString()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UpstreamFailure("Upstream " + what + " request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamFailure("Upstream " + what + " request was interrupted", e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new UpstreamFailure("Upstream " + what + " returned HTTP " + resp.statusCode());
        }
        Log.debugf("Upstream %s succeeded (HTTP %d)", what, resp.statusCode());

        JsonNode body;
        try {
            body = json.readTree(resp.body());
        } catch (IOException e) {
            throw new UpstreamFailure("Upstream " + what + " response is not valid JSON", e);
        }
        @Nullable String accessToken = text(body, "access_token");
        if (accessToken == null) {
            throw new UpstreamFailure("Upstream " + what + " response missing access_token");
        }
        @Nullable String refreshToken = text(body, "refresh_token");
        long expiresIn = body.path("expires_in").asLong(0);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(0, expiresIn));
        return new UpstreamTokens(accessToken, refreshToken == null ? "" : refreshToken, expiresAt);
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String urlEncode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
