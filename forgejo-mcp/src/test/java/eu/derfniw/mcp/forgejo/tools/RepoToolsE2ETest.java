package eu.derfniw.mcp.forgejo.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.derfniw.mcp.forgejo.testsupport.ForgejoTestResource;
import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.crypto.TokenCrypto;
import eu.derfniw.oauthbroker.runtime.crypto.TokenType;
import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives the read-only MCP tools end-to-end over the real MCP protocol (McpAssured streamable
 * transport) against a real Forgejo container. Rather than running the full OAuth dance (covered by
 * {@link eu.derfniw.mcp.forgejo.forgejo.EndToEndOAuthFlowTest}), this mints a broker {@code mcp_at_*}
 * bearer directly with {@link TokenCrypto}, embedding the test user's Forgejo PAT as the upstream
 * token — exercising the bearer auth mechanism, {@code UpstreamBearer}, the REST client, and the
 * tools layer in one path.
 *
 * <p>The tools emit concise plain text (one line per record), so assertions look for the expected
 * tokens in the returned text rather than parsing JSON.
 */
@QuarkusTest
@WithTestResource(ForgejoTestResource.class)
class RepoToolsE2ETest {

    @Inject
    TokenCrypto tokenCrypto;

    private McpStreamableTestClient connect() {
        String bearer = mintAccessToken();
        return McpAssured.newStreamableClient()
                .setMcpPath("/mcp")
                .setOpenSubsidiarySse(false)
                .setAdditionalHeaders(
                        msg -> MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer " + bearer))
                .build()
                .connect();
    }

    private String mintAccessToken() {
        AccessTokenEntry entry = new AccessTokenEntry(
                "https://claude.ai/test-client",
                List.of("read:repository", "read:issue"),
                new UpstreamTokens(
                        ForgejoTestResource.testUserPat(), "", Instant.now().plus(1, ChronoUnit.HOURS)),
                new UpstreamUser(1L, ForgejoTestResource.DEMO_REPO_OWNER, "tester@example.com"),
                Instant.now().plus(1, ChronoUnit.HOURS));
        return tokenCrypto.encode(TokenType.ACCESS_TOKEN, entry);
    }

    private static String text(ToolResponse resp) {
        assertFalse(resp.isError(), "tool call should not be an error");
        return resp.firstContent().asText().text();
    }

    @Test
    void toolsAreListed() {
        try (McpStreamableTestClient client = connect()) {
            client.when()
                    .toolsList(page -> {
                        List<String> names = page.tools().stream()
                                .map(McpAssured.ToolInfo::name)
                                .toList();
                        assertTrue(
                                names.containsAll(
                                        List.of("search_repositories", "get_repository", "list_branches", "read_file")),
                                "expected all repo tools (got: " + names + ")");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void searchRepositoriesFindsDemoRepo() {
        String expected = ForgejoTestResource.DEMO_REPO_OWNER + "/" + ForgejoTestResource.DEMO_REPO_NAME;
        try (McpStreamableTestClient client = connect()) {
            client.when()
                    .toolsCall("search_repositories", Map.of("query", ForgejoTestResource.DEMO_REPO_NAME), resp -> {
                        String out = text(resp);
                        assertTrue(out.contains(expected), "search should surface " + expected + " (got: " + out + ")");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getRepositoryReturnsMetadata() {
        try (McpStreamableTestClient client = connect()) {
            client.when()
                    .toolsCall(
                            "get_repository",
                            Map.of(
                                    "owner", ForgejoTestResource.DEMO_REPO_OWNER,
                                    "repo", ForgejoTestResource.DEMO_REPO_NAME),
                            resp -> {
                                String out = text(resp);
                                String fullName =
                                        ForgejoTestResource.DEMO_REPO_OWNER + "/" + ForgejoTestResource.DEMO_REPO_NAME;
                                assertTrue(out.contains(fullName), "should name the repo (got: " + out + ")");
                                assertTrue(
                                        out.contains("main") && out.contains("public"),
                                        "should report default branch + visibility (got: " + out + ")");
                            })
                    .thenAssertResults();
        }
    }

    @Test
    void listBranchesContainsMain() {
        try (McpStreamableTestClient client = connect()) {
            client.when()
                    .toolsCall(
                            "list_branches",
                            Map.of(
                                    "owner", ForgejoTestResource.DEMO_REPO_OWNER,
                                    "repo", ForgejoTestResource.DEMO_REPO_NAME),
                            resp -> {
                                String out = text(resp);
                                assertTrue(
                                        out.lines().anyMatch(l -> l.startsWith("main  ")),
                                        "branches should list main with its sha (got: " + out + ")");
                            })
                    .thenAssertResults();
        }
    }

    @Test
    void readFileReturnsDecodedReadme() {
        try (McpStreamableTestClient client = connect()) {
            client.when()
                    .toolsCall(
                            "read_file",
                            Map.of(
                                    "owner",
                                    ForgejoTestResource.DEMO_REPO_OWNER,
                                    "repo",
                                    ForgejoTestResource.DEMO_REPO_NAME,
                                    "path",
                                    "README.md",
                                    "ref",
                                    "main"),
                            resp -> {
                                String content = text(resp);
                                assertTrue(
                                        content.contains(ForgejoTestResource.DEMO_REPO_NAME),
                                        "README should mention the repo name; got: <<" + content + ">>");
                            })
                    .thenAssertResults();
        }
    }
}
