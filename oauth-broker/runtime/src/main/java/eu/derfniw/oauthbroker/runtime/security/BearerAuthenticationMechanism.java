package eu.derfniw.oauthbroker.runtime.security;

import eu.derfniw.oauthbroker.runtime.crypto.TokenCrypto;
import eu.derfniw.oauthbroker.runtime.crypto.TokenCryptoException;
import eu.derfniw.oauthbroker.runtime.crypto.TokenType;
import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import eu.derfniw.oauthbroker.runtime.service.BrokerUris;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Set;

/**
 * Quarkus {@link HttpAuthenticationMechanism} for the broker's opaque {@code mcp_at_*} bearer
 * tokens. Decodes the AES-GCM envelope locally via {@link TokenCrypto} (which enforces expiry) and
 * builds a {@link SecurityIdentity} whose principal is the upstream username, whose roles are the
 * granted OAuth scopes, and which carries the embedded upstream bearer + user as attributes so REST
 * client producers can forward upstream calls.
 *
 * <p>Path scoping is done via {@code quarkus.http.auth.permission.mcp.*} in
 * {@code application.properties} — this mechanism only inspects the {@code Authorization} header
 * when present and lets unauthenticated requests through; the permission rule decides whether the
 * absence of an identity is a 401.
 */
@ApplicationScoped
public class BearerAuthenticationMechanism implements HttpAuthenticationMechanism {

    /**
     * SecurityIdentity attribute key for the decoded {@link AccessTokenEntry}. The embedded upstream
     * bearer and resolved user are reachable through it; {@link UpstreamBearer} reads it rather than
     * duplicating the bearer in a second attribute.
     */
    public static final String ATTR_ENTRY = "upstream.accessTokenEntry";

    @Inject
    TokenCrypto tokenCrypto;

    @Inject
    BrokerUris brokerUris;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager idpManager) {
        String header = context.request().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Uni.createFrom().nullItem();
        }
        String token = header.substring("Bearer ".length()).trim();

        AccessTokenEntry entry;
        try {
            entry = tokenCrypto.decode(TokenType.ACCESS_TOKEN, token, AccessTokenEntry.class);
        } catch (TokenCryptoException e) {
            return Uni.createFrom().failure(new AuthenticationFailedException("token invalid or expired"));
        }

        String username = entry.upstreamUser().login();
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> username)
                .addRoles(new HashSet<>(entry.scope()))
                .addAttribute(ATTR_ENTRY, entry)
                .build();
        return Uni.createFrom().item(identity);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        String www = "Bearer realm=\"mcp\", resource_metadata=\"" + brokerUris.protectedResourceMetadataUri() + "\"";
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", www));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        // No IdentityProvider delegation — the SecurityIdentity is built directly from the decoded
        // envelope, so we declare no required credential types and Quarkus skips the IdP existence
        // check.
        return Set.of();
    }
}
