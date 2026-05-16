package eu.derfniw.mcp.forgejo.broker.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.derfniw.mcp.forgejo.broker.model.AccessTokenEntry;
import eu.derfniw.mcp.forgejo.broker.model.AuthCodeEntry;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoTokens;
import eu.derfniw.mcp.forgejo.broker.model.ForgejoUser;
import eu.derfniw.mcp.forgejo.broker.model.PendingAuth;
import eu.derfniw.mcp.forgejo.broker.model.RefreshTokenEntry;
import eu.derfniw.mcp.forgejo.broker.model.TokenIds;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BrokerStoresTest {

    @Inject
    PendingAuthStore pendingAuthStore;

    @Inject
    AuthCodeStore authCodeStore;

    @Inject
    AccessTokenStore accessTokenStore;

    @Inject
    RefreshTokenStore refreshTokenStore;

    @Inject
    RedisDataSource redis;

    @Test
    void pendingAuthRoundTripsAndIsSingleUse() {
        String state = TokenIds.forgejoState();
        PendingAuth original = new PendingAuth(
                "https://claude.ai/cimd/abc",
                URI.create("https://claude.ai/oauth/callback"),
                "claude-state-xyz",
                "Z29vZF9jaGFsbGVuZ2U",
                "S256",
                List.of("read:repository", "read:issue"),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        pendingAuthStore.put(state, original);

        Optional<PendingAuth> first = pendingAuthStore.consume(state);
        assertTrue(first.isPresent(), "first consume returns the value");
        assertEquals(original, first.get(), "round-trip preserves all fields");

        Optional<PendingAuth> second = pendingAuthStore.consume(state);
        assertTrue(second.isEmpty(), "second consume is empty (single-use)");
    }

    @Test
    void authCodeRoundTripsAndIsSingleUse() {
        String code = TokenIds.mcpAuthCode();
        AuthCodeEntry original = sampleAuthCode();

        authCodeStore.put(code, original);

        Optional<AuthCodeEntry> first = authCodeStore.consume(code);
        assertTrue(first.isPresent());
        assertEquals(original, first.get());
        assertTrue(authCodeStore.consume(code).isEmpty(), "single-use");
    }

    @Test
    void accessTokenRoundTripsAndDeleteRemovesIt() {
        String token = TokenIds.mcpAccessToken();
        AccessTokenEntry original = sampleAccessToken();

        accessTokenStore.put(token, original);

        Optional<AccessTokenEntry> read = accessTokenStore.get(token);
        assertTrue(read.isPresent());
        assertEquals(original, read.get());

        // get() must NOT consume — re-read works.
        assertTrue(accessTokenStore.get(token).isPresent(), "get is not destructive");

        accessTokenStore.delete(token);
        assertTrue(accessTokenStore.get(token).isEmpty(), "delete removes the entry");
    }

    @Test
    void accessTokenReplacePreservingTtlKeepsOriginalExpiry() throws InterruptedException {
        String token = TokenIds.mcpAccessToken();
        AccessTokenEntry original = sampleAccessToken();
        accessTokenStore.put(token, original);

        KeyCommands<String> keyCmds = redis.key();
        long ttlBefore = keyCmds.pttl("broker:access_token:" + token);
        assertTrue(ttlBefore > 0, "TTL set on initial put");

        Thread.sleep(50);

        ForgejoTokens refreshedUpstream = new ForgejoTokens(
                "new-forgejo-access",
                "new-forgejo-refresh",
                Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MILLIS));
        AccessTokenEntry replaced = original.withForgejoTokens(refreshedUpstream);
        accessTokenStore.replacePreservingTtl(token, replaced);

        long ttlAfter = keyCmds.pttl("broker:access_token:" + token);
        assertTrue(
                ttlAfter > 0 && ttlAfter <= ttlBefore,
                "TTL preserved (not reset): before=" + ttlBefore + " after=" + ttlAfter);

        Optional<AccessTokenEntry> reread = accessTokenStore.get(token);
        assertTrue(reread.isPresent());
        assertEquals(refreshedUpstream, reread.get().forgejoTokens(), "value updated");
        assertNotEquals(original.forgejoTokens(), reread.get().forgejoTokens());
    }

    @Test
    void refreshTokenRoundTripsAndIsSingleUse() {
        String token = TokenIds.mcpRefreshToken();
        RefreshTokenEntry original = new RefreshTokenEntry(
                "https://claude.ai/cimd/abc",
                List.of("read:repository"),
                sampleForgejoTokens(),
                sampleForgejoUser(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        refreshTokenStore.put(token, original);

        Optional<RefreshTokenEntry> first = refreshTokenStore.consume(token);
        assertTrue(first.isPresent());
        assertEquals(original, first.get());
        assertTrue(refreshTokenStore.consume(token).isEmpty(), "rotated/consumed");
    }

    private static AuthCodeEntry sampleAuthCode() {
        return new AuthCodeEntry(
                "https://claude.ai/cimd/abc",
                URI.create("https://claude.ai/oauth/callback"),
                "Z29vZF9jaGFsbGVuZ2U",
                "S256",
                List.of("read:repository"),
                sampleForgejoTokens(),
                sampleForgejoUser(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static AccessTokenEntry sampleAccessToken() {
        return new AccessTokenEntry(
                "https://claude.ai/cimd/abc",
                List.of("read:repository", "read:issue"),
                sampleForgejoTokens(),
                sampleForgejoUser(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static ForgejoTokens sampleForgejoTokens() {
        return new ForgejoTokens(
                "forgejo-access-abcdef",
                "forgejo-refresh-uvwxyz",
                Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS));
    }

    private static ForgejoUser sampleForgejoUser() {
        return new ForgejoUser(42L, "tester", "tester@test.local");
    }
}
