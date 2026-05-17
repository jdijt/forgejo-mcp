package eu.derfniw.mcp.forgejo.broker.endpoint;

import eu.derfniw.mcp.forgejo.broker.model.BadRequest;
import eu.derfniw.mcp.forgejo.broker.model.BrokerException;
import eu.derfniw.mcp.forgejo.broker.model.OAuthError;
import eu.derfniw.mcp.forgejo.broker.model.UpstreamFailure;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Single JAX-RS exception mapper for the sealed {@link BrokerException} hierarchy. Resources and
 * services never build {@link Response}s for failures themselves — they throw a domain exception
 * and this mapper renders it. The switch is exhaustive over the sealed hierarchy so adding a new
 * leaf will be a compile error here until the rendering is decided.
 */
@Provider
public class BrokerExceptionMapper implements ExceptionMapper<BrokerException> {

    @Override
    public Response toResponse(BrokerException exception) {
        return switch (exception) {
            case BadRequest br -> Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(br.getMessage())
                    .build();
            case OAuthError oe -> oauthErrorRedirect(oe);
            case UpstreamFailure uf -> {
                Log.warnf(uf, "upstream failure");
                yield Response.status(Response.Status.BAD_GATEWAY)
                        .type(MediaType.TEXT_PLAIN)
                        .entity("upstream failure")
                        .build();
            }
        };
    }

    private static Response oauthErrorRedirect(OAuthError e) {
        UriBuilder b = UriBuilder.fromUri(e.redirectUri())
                .queryParam("error", e.errorCode())
                .queryParam("error_description", e.description());
        if (e.state() != null && !e.state().isBlank()) {
            b.queryParam("state", e.state());
        }
        return Response.status(Response.Status.FOUND).location(b.build()).build();
    }
}
