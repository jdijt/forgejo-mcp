package eu.derfniw.mcp.forgejo.forgejo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jspecify.annotations.Nullable;

/**
 * Typed client for the subset of Forgejo's repo API the MCP tools call as the end user. Each
 * method takes an explicit {@code @HeaderParam Authorization} so the call site decides which
 * bearer to forward — production code reads it from the broker's {@code UpstreamBearer}; tests pass
 * a fixture PAT. The base URL is configured under {@code quarkus.rest-client.forgejo-api.url}.
 *
 * <p>DTOs only declare fields the app actually uses; unknown fields are ignored.
 */
@RegisterRestClient(configKey = "forgejo-api")
public interface ForgejoReposApi {

    @GET
    @Path("/api/v1/repos/search")
    @Produces(MediaType.APPLICATION_JSON)
    RepoSearchResults searchRepos(
            @HeaderParam("Authorization") String bearer, @QueryParam("q") String query, @QueryParam("limit") int limit);

    @GET
    @Path("/api/v1/repos/{owner}/{repo}")
    @Produces(MediaType.APPLICATION_JSON)
    Repo getRepo(
            @HeaderParam("Authorization") String bearer,
            @PathParam("owner") String owner,
            @PathParam("repo") String repo);

    @GET
    @Path("/api/v1/repos/{owner}/{repo}/branches")
    @Produces(MediaType.APPLICATION_JSON)
    List<Branch> listBranches(
            @HeaderParam("Authorization") String bearer,
            @PathParam("owner") String owner,
            @PathParam("repo") String repo);

    /**
     * Fetch a file or directory entry at {@code path}. Forgejo returns a single object for files
     * and an array for directories; we model the file case (the common one). Caller is expected
     * to know the path points at a file. {@code ref} is optional (branch / tag / commit sha).
     */
    @GET
    @Path("/api/v1/repos/{owner}/{repo}/contents/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    ContentsEntry getContents(
            @HeaderParam("Authorization") String bearer,
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("path") String path,
            @QueryParam("ref") @Nullable String ref);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepoSearchResults(boolean ok, List<Repo> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repo(
            long id,
            String name,
            @JsonProperty("full_name") String fullName,
            String description,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("private") boolean privateRepo,
            @JsonProperty("fork") boolean fork,
            @JsonProperty("archived") boolean archived,
            @JsonProperty("html_url") String htmlUrl,
            Owner owner) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Owner(long id, String login) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(String name, Commit commit) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Commit(String id, String message) {}
    }

    /**
     * Contents API entry. For text files, {@code content} is base64-encoded and {@code encoding}
     * is {@code "base64"}. For symlinks / submodules the app isn't expected to call this — the
     * tools layer will decide based on {@code type}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentsEntry(
            String name,
            String path,
            String sha,
            String type,
            long size,
            @Nullable String encoding,
            @Nullable String content,
            @JsonProperty("download_url") @Nullable String downloadUrl) {}
}
