package eu.derfniw.oauthbroker.deployment;

import eu.derfniw.oauthbroker.runtime.api.UpstreamTokens;
import eu.derfniw.oauthbroker.runtime.api.UpstreamUser;
import eu.derfniw.oauthbroker.runtime.dto.CimdDocument;
import eu.derfniw.oauthbroker.runtime.envelope.AccessTokenEntry;
import eu.derfniw.oauthbroker.runtime.envelope.AuthCodeEntry;
import eu.derfniw.oauthbroker.runtime.envelope.PendingAuth;
import eu.derfniw.oauthbroker.runtime.envelope.RefreshTokenEntry;
import eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.DotName;

/**
 * Build-time wiring for the OAuth broker extension. The broker's beans, JAX-RS resources, config
 * mappings, and {@code HttpAuthenticationMechanism} are discovered from the Jandex-indexed runtime
 * jar via the standard CDI/REST/Security extensions, so this processor adds only the cross-cutting
 * build-time concerns: the feature marker, native reflection for the encrypted envelope records, and
 * a friendly build-time check that the application supplied the required SPI bean.
 */
class OAuthBrokerProcessor {

    private static final String FEATURE = "oauth-broker";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The envelope payloads are (de)serialized by Jackson <em>inside</em> {@code TokenCrypto} (the
     * plaintext is encrypted to bytes, so they never pass through JAX-RS), which means Quarkus's
     * REST-driven Jackson reflection registration does not cover them. Register them explicitly so
     * the AES-GCM tokens round-trip in a native image.
     */
    @BuildStep
    ReflectiveClassBuildItem envelopePayloads() {
        return ReflectiveClassBuildItem.builder(
                        AccessTokenEntry.class,
                        RefreshTokenEntry.class,
                        AuthCodeEntry.class,
                        PendingAuth.class,
                        UpstreamTokens.class,
                        UpstreamUser.class)
                .constructors()
                .methods()
                .fields()
                .reason(OAuthBrokerProcessor.class.getName() + " — Jackson (de)serialized envelope payloads")
                .build();
    }

    /**
     * The CIMD client-metadata document is fetched and parsed by {@code CimdResolver} via a direct
     * {@code ObjectMapper.readValue} (not through JAX-RS), so — like the envelope payloads — Quarkus's
     * REST-driven Jackson reflection registration does not cover it. Register it so CIMD resolution
     * works in a native image.
     */
    @BuildStep
    ReflectiveClassBuildItem cimdDocument() {
        return ReflectiveClassBuildItem.builder(CimdDocument.class)
                .constructors()
                .methods()
                .fields()
                .reason(OAuthBrokerProcessor.class.getName() + " — Jackson-parsed CIMD document")
                .build();
    }

    /**
     * The broker's {@code OAuthResource} injects an {@link UpstreamUserResolver}, the one
     * application-supplied seam. If the consuming app forgot to provide it, fail the build with a
     * pointed message instead of letting Arc report a generic "Unsatisfied dependency" at runtime.
     */
    @BuildStep
    void validateUserResolverPresent(
            CombinedIndexBuildItem index,
            ValidationPhaseBuildItem validationPhase,
            BuildProducer<ValidationErrorBuildItem> errors) {
        DotName spi = DotName.createSimple(UpstreamUserResolver.class.getName());
        boolean implemented = !index.getIndex().getAllKnownImplementors(spi).isEmpty();
        if (!implemented) {
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException(
                    "The OAuth broker extension requires the application to provide a CDI bean implementing "
                            + UpstreamUserResolver.class.getName()
                            + " (it resolves the end-user identity from the upstream access token in the OAuth"
                            + " callback). No implementation was found on the application's classpath.")));
        }
    }
}
