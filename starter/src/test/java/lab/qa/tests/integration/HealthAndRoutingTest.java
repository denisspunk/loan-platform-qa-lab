package lab.qa.tests.integration;

import io.restassured.http.ContentType;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** Health check and routing: routing tests are about paths, so they are the one place paths appear in a test. */
@Tag("integration")
@DisplayName("Health and routing: what the service answers outside its business endpoints")
class HealthAndRoutingTest extends BaseIT {

    @Test
    @DisplayName("Health check answers UP")
    void healthCheckAnswersUp() {
        loansApi.health()
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @DisplayName("Wrong method on a known path is refused with a JSON error")
    @ParameterizedTest(name = "{0} {1} -> 404")
    @CsvSource({
            "POST,   /health",
            "GET,    /loans",
            "PUT,    /loans/LN-any",
            "DELETE, /loans/LN-any",
            "GET,    /payments",
            "PUT,    /payments",
    })
    void wrongMethodOnAKnownPathIsRefused(String method, String path) {
        loansApi.request(method, path)
                .then().statusCode(404)
                .contentType(ContentType.JSON)
                .body("error", equalTo("no route for " + method + " " + path));
    }

    @Test
    @DisplayName("Path outside the API is refused with 404")
    void pathOutsideTheApiIsRefused() {
        loansApi.request("GET", "/unknown")
                .then().statusCode(404);
    }

    @Test
    @Disabled("F-10: paths outside /loans, /payments and /health get the JDK's text/html page instead of a JSON error")
    @DisplayName("Path outside the API is refused with a JSON error")
    void pathOutsideTheApiIsRefusedWithAJsonError() {
        loansApi.request("GET", "/unknown")
                .then().statusCode(404)
                .contentType(ContentType.JSON)
                .body("error", notNullValue());
    }
}
