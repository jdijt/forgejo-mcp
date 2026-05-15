package eu.derfniw.mcp.forgejo.broker.service;

import com.sun.net.httpserver.HttpServer;
import eu.derfniw.mcp.forgejo.broker.model.CimdDocument;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CimdResolverTest {

    @Inject CimdResolver resolver;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + path; }

    private void respond(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(payload); }
        });
    }

    @Test
    void resolvesValidCimd() {
        String cimdUrl = url("/cimd/claude.json");
        String body = """
                {
                  "client_id": "%s",
                  "client_name": "Claude",
                  "redirect_uris": ["https://claude.ai/oauth/callback"],
                  "client_uri": "https://claude.ai",
                  "contacts": ["security@anthropic.com"]
                }
                """.formatted(cimdUrl);
        respond("/cimd/claude.json", 200, "application/json", body);

        CimdDocument doc = resolver.resolve(cimdUrl);
        assertEquals(cimdUrl, doc.clientId());
        assertEquals("Claude", doc.clientName());
        assertEquals(URI.create("https://claude.ai/oauth/callback"), doc.redirectUris().get(0));
        assertTrue(doc.clientUri().isPresent());
    }

    @Test
    void rejectsMismatchedClientId() {
        String cimdUrl = url("/cimd/bad-id.json");
        respond("/cimd/bad-id.json", 200, "application/json", """
                {"client_id":"https://different.example/client","client_name":"X","redirect_uris":["https://x/cb"]}""");

        CimdException e = assertThrows(CimdException.class, () -> resolver.resolve(cimdUrl));
        assertTrue(e.getMessage().contains("client_id does not match"), e.getMessage());
    }

    @Test
    void rejectsMissingRedirectUris() {
        String cimdUrl = url("/cimd/no-redirects.json");
        respond("/cimd/no-redirects.json", 200, "application/json",
                "{\"client_id\":\"" + cimdUrl + "\",\"client_name\":\"X\",\"redirect_uris\":[]}");

        CimdException e = assertThrows(CimdException.class, () -> resolver.resolve(cimdUrl));
        assertTrue(e.getMessage().contains("redirect_uri"), e.getMessage());
    }

    @Test
    void rejectsMalformedJson() {
        String cimdUrl = url("/cimd/garbage.json");
        respond("/cimd/garbage.json", 200, "application/json", "<html>not json</html>");

        CimdException e = assertThrows(CimdException.class, () -> resolver.resolve(cimdUrl));
        assertTrue(e.getMessage().contains("not valid JSON"), e.getMessage());
    }

    @Test
    void rejectsNon2xxResponses() {
        // No handler registered; server returns 404 for unknown paths.
        String cimdUrl = url("/cimd/missing.json");
        CimdException e = assertThrows(CimdException.class, () -> resolver.resolve(cimdUrl));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void rejectsRelativeOrSchemelessClientId() {
        assertThrows(CimdException.class, () -> resolver.resolve("/just/a/path"));
        assertThrows(CimdException.class, () -> resolver.resolve("not-a-url"));
    }
}
