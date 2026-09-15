package lab.qa.tests.contract;

import lab.qa.clients.LoanJson;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniquePaymentId;
import static org.hamcrest.Matchers.equalTo;

/**
 * Provider side: our responses still match the schemas other squads rely on.
 * Rename a field in LoanView or change an error shape, and this test goes red before anyone else finds out.
 */
@Tag("contract")
@DisplayName("Loans API contract: responses match the published JSON schemas")
class LoanApiSchemaTest extends BaseIT {

    @Test
    @DisplayName("Created loan matches the loan schema")
    void createdLoanMatchesTheLoanSchema() {
        loansApi.createLoan(aLoan().build())
                .then().statusCode(201)
                .body(matchesJsonSchemaInClasspath("schemas/loan.json"));
    }

    @DisplayName("Loan read back matches the loan schema in every phone state")
    @ParameterizedTest(name = "paid {0} of 300 KES -> {1}")
    @CsvSource({
            "0,   LOCKED",
            "100, UNLOCKED",
            "300, RELEASED",
    })
    void loanMatchesTheLoanSchemaInEveryState(long amount, String expectedState) {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        if (amount > 0) {
            paymentSteps.pay(loan, amount);
        }

        loansApi.getLoan(loan.id())
                .then().statusCode(200)
                .body("deviceState", equalTo(expectedState))
                .body(matchesJsonSchemaInClasspath("schemas/loan.json"));
    }

    @Test
    @DisplayName("Accepted payment matches the payment-accepted schema")
    void acceptedPaymentMatchesItsSchema() {
        LoanJson loan = loanSteps.openLoan(aLoan());

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loan.id(), 100))
                .then().statusCode(202)
                .body(matchesJsonSchemaInClasspath("schemas/payment-accepted.json"));
    }

    @Test
    @DisplayName("Validation error matches the error schema")
    void validationErrorMatchesTheErrorSchema() {
        loansApi.postLoanBody("{\"price\": 300, \"dailyRate\": 100}")
                .then().statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }

    @Test
    @DisplayName("Unknown loan error matches the error schema")
    void unknownLoanErrorMatchesTheErrorSchema() {
        loansApi.getLoan("LN-missing")
                .then().statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }

    @Test
    @DisplayName("Wrong method error matches the error schema")
    void wrongMethodErrorMatchesTheErrorSchema() {
        loansApi.request("DELETE", "/loans/LN-any")
                .then().statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }
}
