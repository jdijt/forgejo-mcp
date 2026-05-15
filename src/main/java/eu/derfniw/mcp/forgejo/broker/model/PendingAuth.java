package eu.derfniw.mcp.forgejo.broker.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Server-side state held while the user is bouncing through Forgejo's consent
 * screen. Keyed in Redis by the random {@code state} value we passed to Forgejo
 * (NOT the {@code state} value Claude passed to us — that is stashed in
 * {@link #mcpState} for echo-back when we redirect Claude home).
 */
public record PendingAuth(
        String mcpClientId,
        URI redirectUri,
        String mcpState,
        String codeChallenge,
        String codeChallengeMethod,
        List<String> scope,
        Instant createdAt
) {}
