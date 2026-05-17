package eu.derfniw.mcp.forgejo.broker.model;

import eu.derfniw.mcp.forgejo.broker.crypto.Expirable;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * In-flight authorization request. Encrypted into the {@code state} param we hand to Forgejo;
 * decrypted on the way back at {@code /oauth/callback}. Carries the MCP client's own state for
 * echo-back in {@link #mcpState}.
 */
public record PendingAuth(
        String mcpClientId,
        URI redirectUri,
        String mcpState,
        String codeChallenge,
        String codeChallengeMethod,
        List<String> scope,
        Instant expiresAt)
        implements Expirable {}
