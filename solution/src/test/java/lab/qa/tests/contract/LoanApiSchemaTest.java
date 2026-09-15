package lab.qa.tests.contract;

import lab.qa.core.BaseIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static lab.qa.data.TestData.aLoan;

/**
 * Provider side: GET /loans/{id} still matches the schema other squads rely on.
 * Rename a field in LoanView and this test goes red before anyone else finds out.
 */
@Tag("contract")
class LoanApiSchemaTest extends BaseIT {

    @Test
    void loanResponseMatchesThePublishedSchema() {
        String loanId = loansApi.createLoan(aLoan().build())
                .then().statusCode(201)
                .extract().path("id");

        loansApi.getLoan(loanId)
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/loan.json"));
    }
}
