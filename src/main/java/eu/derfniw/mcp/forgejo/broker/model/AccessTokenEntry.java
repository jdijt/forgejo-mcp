package eu.derfniw.mcp.forgejo.broker.model;

import eu.derfniw.mcp.forgejo.broker.crypto.Expirable;
import java.time.Instant;
import java.util.List;

/**
 * Plaintext payload of the {@code mcp_at_*} bearer Claude presents on every MCP call. Decrypted
 * server-side per request; the embedded Forgejo bearer is forwarded upstream. Broker AT lifetime
 * is intended to be {@code <=} Forgejo's own AT lifetime, so a single decode covers the call.
 */
public record AccessTokenEntry(
        String mcpClientId, List<String> scope, ForgejoTokens forgejoTokens, ForgejoUser forgejoUser, Instant expiresAt)
        implements Expirable {}
