package eu.derfniw.mcp.forgejo.broker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.derfniw.mcp.forgejo.broker.model.BadRequest;
import eu.derfniw.mcp.forgejo.broker.model.CimdDocument;
import eu.derfniw.mcp.forgejo.broker.model.CimdValidationError;
import eu.derfniw.mcp.forgejo.broker.model.UpstreamFailure;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/**
 * Fetches and validates a Client ID Metadata Document from the URL an MCP client used as its
 * {@code client_id}. Honors a configurable host allowlist (when set) and a fetch timeout from
 * {@link BrokerConfig}. Translates everything into domain exceptions: caller-side problems become
 * {@link BadRequest}; transport failures become {@link UpstreamFailure}.
 */
@ApplicationScoped
public class CimdResolver {

    private final BrokerConfig brokerConfig;
    private final ObjectMapper json;
    private final HttpClient http;

    public CimdResolver(BrokerConfig brokerConfig, ObjectMapper objectMapper) {
        this.brokerConfig = brokerConfig;
        this.json = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(brokerConfig.cimd().fetchTimeout())
                .build();
    }

    public CimdDocument resolve(String clientIdUrl) {
        URI uri;
        try {
            uri = URI.create(clientIdUrl);
        } catch (IllegalArgumentException e) {
            throw new CimdValidationError("client_id is not a valid URL: " + clientIdUrl, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new CimdValidationError("client_id URL must be absolute: " + clientIdUrl);
        }
        brokerConfig.cimd().allowedHosts().ifPresent(allowedHosts -> {
            if (!allowedHosts.contains(uri.getHost())) {
                throw new CimdValidationError("CIMD Host not allowed");
            }
        });

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(brokerConfig.cimd().fetchTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new UpstreamFailure("CIMD fetch timed out: " + clientIdUrl, e);
        } catch (Exception e) {
            throw new UpstreamFailure("CIMD fetch failed: " + clientIdUrl, e);
        }

        if (resp.statusCode() != 200 && resp.statusCode() < 500) {
            throw new CimdValidationError("CIMD fetch returned HTTP " + resp.statusCode() + " for " + clientIdUrl);
        } else if (resp.statusCode() >= 500) {
            throw new UpstreamFailure(
                    "CIMD fetch failed with remote server error: " + resp.statusCode() + " for " + clientIdUrl);
        }

        CimdDocument doc;
        try {
            doc = json.readValue(resp.body(), CimdDocument.class);
        } catch (IOException e) {
            Log.warnf(
                    "CIMD parse failed for %s; status=%d, content-type=%s, body=<<%s>>",
                    clientIdUrl,
                    resp.statusCode(),
                    resp.headers().firstValue("content-type").orElse("<none>"),
                    resp.body());
            throw new CimdValidationError("CIMD body is not valid JSON: " + clientIdUrl, e);
        }

        if (!doc.clientId().equals(clientIdUrl)) {
            throw new CimdValidationError(
                    "CIMD client_id does not match URL: doc=" + doc.clientId() + " url=" + clientIdUrl);
        }
        if (doc.redirectUris().isEmpty()) {
            throw new CimdValidationError("CIMD must declare at least one redirect_uri: " + clientIdUrl);
        }

        Log.debugf(
                "Resolved CIMD for %s (name=%s, redirects=%d)",
                clientIdUrl, doc.clientName(), doc.redirectUris().size());
        return doc;
    }
}
