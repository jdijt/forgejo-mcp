package eu.derfniw.oauthbroker.runtime.envelope;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.crypto.Expirable;
import java.time.Instant;
import java.util.List;

/**
 * Plaintext payload of the {@code mcp_at_*} bearer the client presents on every MCP call. Decrypted
 * server-side per request; the embedded upstream bearer is forwarded upstream. Broker AT lifetime is
 * intended to be {@code <=} the upstream's own AT lifetime, so a single decode covers the call.
 */
public record AccessTokenEntry(
        String mcpClientId,
        List<String> scope,
        UpstreamTokens upstreamTokens,
        UpstreamUser upstreamUser,
        Instant expiresAt)
        implements Expirable {}
