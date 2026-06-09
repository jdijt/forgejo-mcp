package eu.derfniw.mcp.forgejo.forgejo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.derfniw.mcp.forgejo.testsupport.ForgejoTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(ForgejoTestResource.class)
class ForgejoReposApiTest {

    @Inject
    @RestClient
    ForgejoReposApi repos;

    private String bearer() {
        return "Bearer " + ForgejoTestResource.testUserPat();
    }

    @Test
    void searchFindsDemoRepo() {
        ForgejoReposApi.RepoSearchResults results = repos.searchRepos(bearer(), ForgejoTestResource.DEMO_REPO_NAME, 50);
        assertTrue(results.ok(), "search ok flag");
        List<String> fullNames =
                results.data().stream().map(ForgejoReposApi.Repo::fullName).toList();
        String expected = ForgejoTestResource.DEMO_REPO_OWNER + "/" + ForgejoTestResource.DEMO_REPO_NAME;
        assertTrue(fullNames.contains(expected), "search should surface " + expected + " (got: " + fullNames + ")");
    }

    @Test
    void getRepoReturnsMetadata() {
        ForgejoReposApi.Repo repo =
                repos.getRepo(bearer(), ForgejoTestResource.DEMO_REPO_OWNER, ForgejoTestResource.DEMO_REPO_NAME);
        assertEquals(ForgejoTestResource.DEMO_REPO_NAME, repo.name());
        assertEquals(ForgejoTestResource.DEMO_REPO_OWNER + "/" + ForgejoTestResource.DEMO_REPO_NAME, repo.fullName());
        assertEquals("main", repo.defaultBranch());
        assertEquals(ForgejoTestResource.DEMO_REPO_OWNER, repo.owner().login());
        assertFalse(repo.archived());
        assertFalse(repo.fork());
    }

    @Test
    void listBranchesContainsMain() {
        List<ForgejoReposApi.Branch> branches =
                repos.listBranches(bearer(), ForgejoTestResource.DEMO_REPO_OWNER, ForgejoTestResource.DEMO_REPO_NAME);
        List<String> names = branches.stream().map(ForgejoReposApi.Branch::name).toList();
        assertTrue(names.contains("main"), "branches should contain main (got: " + names + ")");
        // auto_init produces a non-empty initial commit; sha is set.
        ForgejoReposApi.Branch main = branches.stream()
                .filter(b -> b.name().equals("main"))
                .findFirst()
                .orElseThrow();
        assertNotNull(main.commit().id());
        assertFalse(main.commit().id().isBlank());
    }

    @Test
    void getContentsReturnsReadme() {
        ForgejoReposApi.ContentsEntry entry = repos.getContents(
                bearer(), ForgejoTestResource.DEMO_REPO_OWNER, ForgejoTestResource.DEMO_REPO_NAME, "README.md", "main");
        assertEquals("README.md", entry.name());
        assertEquals("README.md", entry.path());
        assertEquals("file", entry.type());
        assertEquals("base64", entry.encoding());
        assertNotNull(entry.content());
        String decoded =
                new String(Base64.getDecoder().decode(entry.content().replace("\n", "")), StandardCharsets.UTF_8);
        // auto_init seeds a README starting with the repo name as an H1.
        assertTrue(
                decoded.contains(ForgejoTestResource.DEMO_REPO_NAME),
                "README should mention the repo name; got: <<" + decoded + ">>");
    }
}
