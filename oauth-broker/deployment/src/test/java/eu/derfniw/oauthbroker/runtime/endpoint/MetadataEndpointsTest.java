package eu.derfniw.oauthbroker.runtime.endpoint;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MetadataEndpointsTest {

    @Test
    void authorizationServerMetadataAdvertisesRequiredFields() {
        given().accept("application/json")
                .when()
                .get("/.well-known/oauth-authorization-server")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("issuer", equalTo("http://localhost:8081"))
                .body("authorization_endpoint", equalTo("http://localhost:8081/authorize"))
                .body("token_endpoint", equalTo("http://localhost:8081/token"))
                .body("response_types_supported", containsInAnyOrder("code"))
                .body("grant_types_supported", containsInAnyOrder("authorization_code", "refresh_token"))
                .body("code_challenge_methods_supported", containsInAnyOrder("S256"))
                .body("token_endpoint_auth_methods_supported", containsInAnyOrder("none"))
                .body("scopes_supported", hasItem("read:repository"))
                .body("client_id_metadata_document_supported", is(true));
    }

    @Test
    void protectedResourceMetadataPointsAtTheBrokerAS() {
        given().accept("application/json")
                .when()
                .get("/.well-known/oauth-protected-resource/mcp")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("resource", equalTo("http://localhost:8081/mcp"))
                .body("authorization_servers", containsInAnyOrder("http://localhost:8081"))
                .body("bearer_methods_supported", containsInAnyOrder("header"))
                .body("scopes_supported", hasItem("read:repository"));
    }
}
