package eu.derfniw.mcp.forgejo.broker.model;

import java.time.Instant;
import java.util.List;

/**
 * Backing data for an mcp refresh token. On refresh-grant the entry is consumed
 * (rotated) and a fresh AccessTokenEntry + RefreshTokenEntry pair is minted from
 * its contents.
 */
public record RefreshTokenEntry(
        String mcpClientId,
        List<String> scope,
        ForgejoTokens forgejoTokens,
        ForgejoUser forgejoUser,
        Instant createdAt) {
    public AccessTokenEntry toAccessTokenEntry() {
        return new AccessTokenEntry(mcpClientId, scope, forgejoTokens, forgejoUser, createdAt);
    }
}
