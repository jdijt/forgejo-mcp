package eu.derfniw.oauthbroker.runtime.envelope;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.crypto.Expirable;
import java.time.Instant;
import java.util.List;

/**
 * Plaintext payload of the {@code mcp_rt_*} refresh token. On {@code /token} (refresh grant) the
 * embedded upstream refresh token is used to call the upstream's refresh endpoint; a fresh AT/RT
 * pair is then minted from the new upstream tokens.
 */
public record RefreshTokenEntry(
        String mcpClientId,
        List<String> scope,
        UpstreamTokens upstreamTokens,
        UpstreamUser upstreamUser,
        Instant expiresAt)
        implements Expirable {}
