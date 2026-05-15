package eu.derfniw.mcp.forgejo.broker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * RFC 8414 Authorization Server Metadata, plus the non-standard
 * {@code client_id_metadata_document_supported} flag we use to advertise CIMD
 * support to MCP clients (Claude looks for this).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record AuthServerMetadata(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        List<String> responseTypesSupported,
        List<String> grantTypesSupported,
        List<String> codeChallengeMethodsSupported,
        List<String> tokenEndpointAuthMethodsSupported,
        List<String> scopesSupported,
        boolean clientIdMetadataDocumentSupported
) {}
