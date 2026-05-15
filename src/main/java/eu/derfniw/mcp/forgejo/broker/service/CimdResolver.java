package eu.derfniw.mcp.forgejo.broker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.derfniw.mcp.forgejo.broker.model.CimdDocument;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Optional;

/**
 * Fetches and validates a Client ID Metadata Document from the URL an MCP
 * client used as its {@code client_id}. Honors a configurable host allowlist
 * (when set) and a fetch timeout from {@link BrokerConfig}.
 */
@ApplicationScoped
public class CimdResolver {

    private static final Logger LOG = Logger.getLogger(CimdResolver.class);

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
            throw new CimdException("client_id is not a valid URL: " + clientIdUrl, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new CimdException("client_id URL must be absolute: " + clientIdUrl);
        }
        ensureHostAllowed(uri);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(brokerConfig.cimd().fetchTimeout())
                .header("Accept", "application/json")
                .GET().build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new CimdException("CIMD fetch timed out: " + clientIdUrl, e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CimdException("CIMD fetch failed: " + clientIdUrl, e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new CimdException("CIMD fetch returned HTTP " + resp.statusCode() + " for " + clientIdUrl);
        }

        CimdDocument doc;
        try {
            doc = json.readValue(resp.body(), CimdDocument.class);
        } catch (IOException e) {
            throw new CimdException("CIMD body is not valid JSON: " + clientIdUrl, e);
        }

        validate(doc, clientIdUrl);
        LOG.debugf("Resolved CIMD for %s (name=%s, redirects=%d)", clientIdUrl, doc.clientName(), doc.redirectUris().size());
        return doc;
    }

    private void ensureHostAllowed(URI uri) {
        Optional<List<String>> allowed = brokerConfig.cimd().allowedHosts();
        if (allowed.isPresent() && !allowed.get().contains(uri.getHost())) {
            throw new CimdException("CIMD host not in allowlist: " + uri.getHost());
        }
    }

    private static void validate(CimdDocument doc, String clientIdUrl) {
        if (doc.clientId() == null || !doc.clientId().equals(clientIdUrl)) {
            throw new CimdException("CIMD client_id does not match URL: doc=" + doc.clientId() + " url=" + clientIdUrl);
        }
        if (doc.redirectUris() == null || doc.redirectUris().isEmpty()) {
            throw new CimdException("CIMD must declare at least one redirect_uri: " + clientIdUrl);
        }
    }
}
