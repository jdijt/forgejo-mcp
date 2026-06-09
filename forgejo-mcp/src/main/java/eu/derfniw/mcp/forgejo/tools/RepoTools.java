package eu.derfniw.mcp.forgejo.tools;

import eu.derfniw.mcp.forgejo.forgejo.ForgejoReposApi;
import eu.derfniw.oauthbroker.runtime.security.UpstreamBearer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jspecify.annotations.Nullable;

/**
 * Read-only MCP tools over a Forgejo instance: search repositories, fetch repository metadata, list
 * branches, and read a file at a ref. Each call forwards the end user's upstream bearer (decoded by
 * the broker from the inbound {@code mcp_at_*} token) so Forgejo applies the user's own access
 * rights.
 *
 * <p>Output is concise plain text aimed at an LLM consumer rather than JSON: one line per record for
 * collections, a short block for a single repository, and the raw decoded file for {@code read_file}.
 * Free-text fields (descriptions, commit messages) are collapsed to a single line so a record never
 * spans multiple lines.
 */
public class RepoTools {

    /** Forgejo's default {@code /repos/search} page cap; we mirror it as the tool default. */
    private static final int DEFAULT_SEARCH_LIMIT = 30;

    @Inject
    @RestClient
    ForgejoReposApi repos;

    @Inject
    UpstreamBearer upstreamBearer;

    @Tool(
            name = "search_repositories",
            description =
                    "Search repositories on the Forgejo instance by a free-text query (matches repository names). "
                            + "Returns one line per repository the authenticated user can see: "
                            + "owner/name  (default-branch, visibility)  description.",
            annotations = @Tool.Annotations(readOnlyHint = true))
    String searchRepositories(
            @ToolArg(description = "Free-text query matched against repository names.") String query,
            @ToolArg(
                            required = false,
                            description = "Maximum number of results to return (default 30).",
                            defaultValue = "30")
                    @Nullable
                    Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_SEARCH_LIMIT : limit;
        List<ForgejoReposApi.Repo> data =
                repos.searchRepos(bearer(), query, effectiveLimit).data();
        if (data.isEmpty()) {
            return "No repositories found.";
        }
        return data.stream().map(RepoTools::repoLine).collect(Collectors.joining("\n"));
    }

    @Tool(
            name = "get_repository",
            description = "Fetch metadata for a single repository identified by its owner and name.",
            annotations = @Tool.Annotations(readOnlyHint = true))
    String getRepository(
            @ToolArg(description = "Repository owner (user or organization).") String owner,
            @ToolArg(description = "Repository name.") String repo) {
        ForgejoReposApi.Repo r = repos.getRepo(bearer(), owner, repo);
        List<String> lines = new ArrayList<>();
        lines.add(r.fullName() + "  " + flags(r));
        String description = oneLine(r.description());
        if (!description.isBlank()) {
            lines.add(description);
        }
        lines.add(r.htmlUrl());
        return String.join("\n", lines);
    }

    @Tool(
            name = "list_branches",
            description = "List the branches of a repository, one per line: name  commit-sha  tip-commit-message.",
            annotations = @Tool.Annotations(readOnlyHint = true))
    String listBranches(
            @ToolArg(description = "Repository owner (user or organization).") String owner,
            @ToolArg(description = "Repository name.") String repo) {
        List<ForgejoReposApi.Branch> branches = repos.listBranches(bearer(), owner, repo);
        if (branches.isEmpty()) {
            return "No branches found.";
        }
        return branches.stream().map(RepoTools::branchLine).collect(Collectors.joining("\n"));
    }

    @Tool(
            name = "read_file",
            description = "Read the contents of a text file in a repository at an optional ref (branch, tag, or commit "
                    + "SHA; defaults to the repository's default branch). Returns the decoded UTF-8 text.",
            annotations = @Tool.Annotations(readOnlyHint = true))
    ToolResponse readFile(
            @ToolArg(description = "Repository owner (user or organization).") String owner,
            @ToolArg(description = "Repository name.") String repo,
            @ToolArg(description = "File path within the repository, e.g. src/main/Foo.java.") String path,
            @ToolArg(required = false, description = "Branch, tag, or commit SHA. Defaults to the default branch.")
                    @Nullable
                    String ref) {
        String effectiveRef = (ref == null || ref.isBlank()) ? null : ref;
        ForgejoReposApi.ContentsEntry entry;
        try {
            entry = repos.getContents(bearer(), owner, repo, path, effectiveRef);
        } catch (WebApplicationException e) {
            return ToolResponse.error("Could not read " + path + ": " + e.getMessage());
        }
        if (!"file".equals(entry.type())) {
            return ToolResponse.error(path + " is not a file (type: " + entry.type() + ").");
        }
        String content = entry.content();
        if (!"base64".equals(entry.encoding()) || content == null) {
            return ToolResponse.error(path + " has no readable text content (encoding: " + entry.encoding() + ").");
        }
        byte[] decoded = Base64.getMimeDecoder().decode(content);
        return ToolResponse.success(new String(decoded, StandardCharsets.UTF_8));
    }

    private String bearer() {
        return upstreamBearer.header();
    }

    /** {@code owner/name  (default-branch, visibility[, fork][, archived])  description}. */
    private static String repoLine(ForgejoReposApi.Repo r) {
        String line = r.fullName() + "  " + flags(r);
        String description = oneLine(r.description());
        return description.isBlank() ? line : line + "  " + description;
    }

    /** {@code (default-branch, visibility[, fork][, archived])}. */
    private static String flags(ForgejoReposApi.Repo r) {
        List<String> parts = new ArrayList<>();
        parts.add(r.defaultBranch());
        parts.add(r.privateRepo() ? "private" : "public");
        if (r.fork()) {
            parts.add("fork");
        }
        if (r.archived()) {
            parts.add("archived");
        }
        return "(" + String.join(", ", parts) + ")";
    }

    /** {@code name  sha  tip-commit-message}. */
    private static String branchLine(ForgejoReposApi.Branch b) {
        return b.name() + "  " + b.commit().id() + "  " + oneLine(b.commit().message());
    }

    /** Collapse runs of whitespace (incl. newlines) to single spaces so a value stays on one line. */
    private static String oneLine(@Nullable String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}
