package eu.derfniw.mcp.forgejo.broker.model;

import eu.derfniw.mcp.forgejo.broker.crypto.Expirable;
import java.time.Instant;
import java.util.List;

/**
 * Plaintext payload of the {@code mcp_rt_*} refresh token. On {@code /token} (refresh grant) the
 * embedded Forgejo refresh token is used to call Forgejo's refresh endpoint; a fresh AT/RT pair
 * is then minted from the new Forgejo tokens.
 */
public record RefreshTokenEntry(
        String mcpClientId, List<String> scope, ForgejoTokens forgejoTokens, ForgejoUser forgejoUser, Instant expiresAt)
        implements Expirable {}
