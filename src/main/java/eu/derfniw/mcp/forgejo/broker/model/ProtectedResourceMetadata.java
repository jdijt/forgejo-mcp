package eu.derfniw.mcp.forgejo.broker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * RFC 9728 Protected Resource Metadata. Served from
 * /.well-known/oauth-protected-resource{resource-path} so MCP clients can
 * discover which authorization server protects the MCP endpoint.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record ProtectedResourceMetadata(
        String resource,
        List<String> authorizationServers,
        List<String> bearerMethodsSupported,
        List<String> scopesSupported
) {}
