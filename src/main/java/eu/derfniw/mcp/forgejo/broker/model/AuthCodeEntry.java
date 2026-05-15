package eu.derfniw.mcp.forgejo.broker.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Short-lived entry returned to Claude as an OAuth authorization code. Exchanged
 * at /token along with the PKCE verifier. Single-use: consumed via getdel on
 * exchange.
 */
public record AuthCodeEntry(
        String mcpClientId,
        URI redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        List<String> scope,
        ForgejoTokens forgejoTokens,
        ForgejoUser forgejoUser,
        Instant createdAt
) {}
