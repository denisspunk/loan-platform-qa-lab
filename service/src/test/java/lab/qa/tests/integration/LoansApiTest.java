package lab.qa.tests.integration;

import lab.qa.clients.LoanJson;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniqueImei;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@Tag("integration")
@DisplayName("Loans API: create and read a loan")
class LoansApiTest extends BaseIT {

    @Test
    @DisplayName("New loan starts active, with a locked phone and the whole price to pay")
    void newLoanStartsWithALockedPhone() {
        LoanJson loan = loansApi.createLoan(aLoan().price(12_000).dailyRate(100).build())
                .then().statusCode(201)
                .extract().as(LoanJson.class);

        assertSoftly(softly -> {
            softly.assertThat(loan.id()).startsWith("LN-");
            softly.assertThat(loan.price()).isEqualTo(12_000);
            softly.assertThat(loan.dailyRate()).isEqualTo(100);
            softly.assertThat(loan.paid()).isZero();
            softly.assertThat(loan.balance()).isEqualTo(12_000);
            softly.assertThat(loan.credit()).isZero();
            softly.assertThat(loan.status()).isEqualTo("ACTIVE");
            softly.assertThat(loan.deviceState()).isEqualTo("LOCKED");
            softly.assertThat(loan.unlockedUntil()).isNull();
        });
    }

    @Test
    @DisplayName("Created loan is read back unchanged")
    void createdLoanIsReadBackUnchanged() {
        LoanJson created = loanSteps.openLoan(aLoan());

        LoanJson read = loansApi.getLoan(created.id())
                .then().statusCode(200)
                .extract().as(LoanJson.class);

        assertThat(read).isEqualTo(created);
    }

    @Test
    @DisplayName("Unknown loan is not found")
    void unknownLoanIsNotFound() {
        loansApi.getLoan("LN-missing")
                .then().statusCode(404)
                .body("error", equalTo("loan LN-missing not found"));
    }

    @Test
    @DisplayName("Empty loan id is answered as an unknown loan (F-08)")
    void emptyLoanIdIsAnsweredAsAnUnknownLoan() {
        loansApi.getLoan("")
                .then().statusCode(404)
                .body("error", equalTo("loan  not found"));
    }

    @DisplayName("Loan with a missing or invalid field is rejected")
    @ParameterizedTest(name = "{2} -> 400 \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            {"price": 300, "dailyRate": 100}                                 | deviceId is required       | no deviceId
            {"deviceId": " ", "price": 300, "dailyRate": 100}                | deviceId is required       | blank deviceId
            {"deviceId": "350000000000001", "price": 0, "dailyRate": 100}    | price must be positive     | price 0
            {"deviceId": "350000000000001", "price": -300, "dailyRate": 100} | price must be positive     | negative price
            {"deviceId": "350000000000001", "price": 300, "dailyRate": 0}    | dailyRate must be positive | dailyRate 0
            {"deviceId": "350000000000001", "price": 300}                    | dailyRate must be positive | no dailyRate, reported as zero (F-11)
            """)
    void loanWithAMissingOrInvalidFieldIsRejected(String body, String error, String caseName) {
        loansApi.postLoanBody(body)
                .then().statusCode(400)
                .body("error", equalTo(error));
    }

    @Test
    @DisplayName("Loan body that is not JSON is rejected")
    void loanBodyThatIsNotJsonIsRejected() {
        loansApi.postLoanBody("{not json")
                .then().statusCode(400)
                .body("error", startsWith("invalid JSON"));
    }

    @Test
    @Disabled("F-12: the error still carries Jackson's own message after 'invalid JSON:'")
    @DisplayName("Broken JSON error does not expose parser details")
    void brokenJsonErrorDoesNotExposeParserDetails() {
        loansApi.postLoanBody("{not json")
                .then().statusCode(400)
                .body("error", equalTo("invalid JSON"));
    }

    @Test
    @DisplayName("Unknown fields in the loan body are ignored (F-11)")
    void unknownFieldsAreIgnored() {
        String body = "{\"deviceId\": \"" + uniqueImei() + "\", \"price\": 300, \"dailyRate\": 100, \"colour\": \"red\"}";

        loansApi.postLoanBody(body)
                .then().statusCode(201);
    }
}
