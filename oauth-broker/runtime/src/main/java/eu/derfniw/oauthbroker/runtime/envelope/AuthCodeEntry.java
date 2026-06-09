package eu.derfniw.oauthbroker.runtime.envelope;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.crypto.Expirable;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Short-lived envelope handed back to the MCP client as the broker's authorization code. Exchanged
 * at {@code /token} along with the PKCE verifier. We accept that without a replay store the same
 * code can be used twice within the TTL window — TTL is therefore kept very short.
 */
public record AuthCodeEntry(
        String mcpClientId,
        URI redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        List<String> scope,
        UpstreamTokens upstreamTokens,
        UpstreamUser upstreamUser,
        Instant expiresAt)
        implements Expirable {}
