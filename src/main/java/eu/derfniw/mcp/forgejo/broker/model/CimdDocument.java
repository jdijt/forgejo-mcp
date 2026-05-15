package eu.derfniw.mcp.forgejo.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Client ID Metadata Document — JSON document an MCP client publishes at the
 * URL it uses as its OAuth {@code client_id}. Only the fields we care about for
 * /authorize validation and audit display are modelled; unknown fields are
 * ignored.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CimdDocument(
        String clientId,
        String clientName,
        List<URI> redirectUris,
        Optional<URI> clientUri,
        Optional<URI> logoUri,
        Optional<URI> tosUri,
        Optional<URI> policyUri,
        Optional<List<String>> contacts,
        Optional<String> scope
) {}
