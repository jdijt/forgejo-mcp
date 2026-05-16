package eu.derfniw.mcp.forgejo.broker.model;

import java.time.Instant;
import java.util.List;

/**
 * Backing data for an mcp_access_token. Looked up on every authenticated MCP
 * request; the upstream Forgejo bearer is refreshed in place (with KEEPTTL) when
 * it expires.
 */
public record AccessTokenEntry(
        String mcpClientId,
        List<String> scope,
        ForgejoTokens forgejoTokens,
        ForgejoUser forgejoUser,
        Instant createdAt) {
    public AccessTokenEntry withForgejoTokens(ForgejoTokens refreshed) {
        return new AccessTokenEntry(mcpClientId, scope, refreshed, forgejoUser, createdAt);
    }
}
