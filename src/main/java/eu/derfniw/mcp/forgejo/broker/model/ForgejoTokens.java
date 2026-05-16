package eu.derfniw.mcp.forgejo.broker.model;

import java.time.Instant;

public record ForgejoTokens(String accessToken, String refreshToken, Instant accessExpiresAt) {}
