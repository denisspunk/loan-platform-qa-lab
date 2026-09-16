package lab.qa.tests.regression;

import lab.qa.clients.LoanJson;
import lab.qa.clients.PaymentHistoryJson.PaymentJson;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.StandBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.time.Instant;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniquePaymentId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * The business rules on a deployed stand, over HTTP only: no test clock, no partner stub, no peeking
 * inside. Dates are compared with each other, not with a fixed moment. Partner calls cannot be seen.
 *
 * <p>Tests that change nothing on the stand also carry {@code @Tag("readonly")}. That subset is the
 * only one prod is allowed to run: it cannot create a loan or take a payment, so it can check the
 * contract of a release without leaving anything behind.
 */
@Tag("regression")
@DisplayName("Stand regression: business rules on a deployed stand, over HTTP only")
class StandRegressionTest extends StandBase {

    // --- the API refuses what it must refuse ----------------------------------------------------

    @DisplayName("Loan with a missing or invalid field is rejected with a JSON error")
    @ParameterizedTest(name = "{2} -> 400 \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            {"price": 300, "dailyRate": 100}                              | deviceId is required       | no deviceId
            {"deviceId": "350000000000001", "price": 0, "dailyRate": 100} | price must be positive     | price 0
            {"deviceId": "350000000000001", "price": 300, "dailyRate": 0} | dailyRate must be positive | dailyRate 0
            """)
    void invalidLoanIsRejected(String body, String error, String caseName) {
        loansApi.postLoanBody(body)
                .then().statusCode(400)
                .body("error", equalTo(error))
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }

    @DisplayName("Payment with a zero or negative amount is rejected")
    @ParameterizedTest(name = "amount {0} -> 400")
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountIsRejected(long amount) {
        LoanJson loan = loanSteps.openLoan(aLoan());

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loan.id(), amount))
                .then().statusCode(400)
                .body("error", equalTo("amount must be positive"));
    }

    @Test
    @DisplayName("Unknown loan and payment for an unknown loan are not found")
    void unknownLoanIsNotFound() {
        loansApi.getLoan("LN-missing")
                .then().statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), "LN-missing", 100))
                .then().statusCode(404)
                .body("error", equalTo("loan LN-missing not found"));
    }

    @Test
    @DisplayName("Wrong method on a known path is refused with a JSON error")
    void wrongMethodIsRefused() {
        loansApi.request("DELETE", "/loans/LN-any")
                .then().statusCode(404)
                .body("error", equalTo("no route for DELETE /loans/LN-any"));
    }

    @DisplayName("Loans list refuses a limit that is not a number from 1 to 100")
    @ParameterizedTest(name = "limit={0} -> 400")
    @ValueSource(strings = {"0", "101", "-1", "abc", ""})
    @Tag("readonly")
    void loansListRefusesAnInvalidLimit(String limit) {
        loansApi.listLoans(limit)
                .then().statusCode(400)
                .body("error", equalTo("limit must be a number from 1 to 100"))
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }

    @Test
    @DisplayName("Empty loan id is answered as an unknown loan (F-08)")
    @Tag("readonly")
    @Tag("F-08")
    void emptyLoanIdIsAnsweredAsAnUnknownLoan() {
        loansApi.getLoan("")
                .then().statusCode(404)
                .body("error", equalTo("loan  not found"))
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }

    @Test
    @DisplayName("Loan body that is not JSON is rejected, and nothing is opened")
    @Tag("readonly")
    void loanBodyThatIsNotJsonIsRejected() {
        loansApi.postLoanBody("not json at all")
                .then().statusCode(400)
                .body("error", startsWith("invalid JSON"))
                .body(matchesJsonSchemaInClasspath("schemas/error.json"));
    }

    // --- responses keep their published shape ---------------------------------------------------

    @Test
    @DisplayName("Loan and accepted payment match their JSON schemas")
    void responsesMatchTheirSchemas() {
        String loanId = loansApi.createLoan(aLoan().build())
                .then().statusCode(201)
                .body(matchesJsonSchemaInClasspath("schemas/loan.json"))
                .extract().path("id");

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loanId, 100))
                .then().statusCode(202)
                .body(matchesJsonSchemaInClasspath("schemas/payment-accepted.json"));
    }

    // --- payment rules, end to end on the stand -------------------------------------------------

    @Test
    @DisplayName("Small payments buy a day only when they add up")
    void smallPaymentsBuyADayOnlyWhenTheyAddUp() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson afterSixty = paymentSteps.pay(loan, 60);
        LoanJson afterHundredTwenty = paymentSteps.pay(loan, 60);

        assertSoftly(softly -> {
            softly.assertThat(afterSixty.deviceState()).as("after 60 KES").isEqualTo("LOCKED");
            softly.assertThat(afterSixty.credit()).as("credit after 60 KES").isEqualTo(60);
            softly.assertThat(afterHundredTwenty.deviceState()).as("after 120 KES").isEqualTo("UNLOCKED");
            softly.assertThat(afterHundredTwenty.credit()).as("credit after 120 KES").isEqualTo(20);
        });
    }

    @Test
    @DisplayName("Paying again while unlocked adds exactly one day to the old relock time")
    void payingAgainWhileUnlockedAddsADay() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson first = paymentSteps.pay(loan, 100);
        LoanJson second = paymentSteps.pay(loan, 100);

        // the stand's clock is real, so compare the two relock times with each other, not with a fixed date
        assertSoftly(softly -> softly.assertThat(
                        Duration.between(Instant.parse(first.unlockedUntil()), Instant.parse(second.unlockedUntil())))
                .as("second relock time minus the first")
                .isEqualTo(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("Redelivered callback is applied once")
    void duplicateCallbackIsAppliedOnce() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson after = paymentSteps.payTwiceWithSameId(loan, 500);

        assertSoftly(softly -> {
            softly.assertThat(after.paid()).isEqualTo(501);
            softly.assertThat(after.credit()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Payment that covers the price releases the phone for good")
    void paymentThatCoversThePriceReleasesThePhone() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));

        LoanJson paid = paymentSteps.pay(loan, 300);

        assertSoftly(softly -> {
            softly.assertThat(paid.status()).isEqualTo("PAID_OFF");
            softly.assertThat(paid.deviceState()).isEqualTo("RELEASED");
            softly.assertThat(paid.balance()).isZero();
            softly.assertThat(paid.unlockedUntil()).isNull();
        });
    }

    @Test
    @DisplayName("Payment to a paid-off loan is accepted but changes nothing (F-01)")
    @Tag("F-01")
    void paymentToAPaidOffLoanChangesNothing() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        paymentSteps.pay(loan, 300);

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loan.id(), 1_000))
                .then().statusCode(202);
        // one service instance with one event-bus thread on the stand, so the marker payment waits for this one
        paymentSteps.waitForQueuedPayments();

        LoanJson after = loanSteps.current(loan);
        assertSoftly(softly -> {
            softly.assertThat(after.paid()).isEqualTo(300);
            softly.assertThat(after.status()).isEqualTo("PAID_OFF");
        });
    }

    // --- what the web UI reads -------------------------------------------------------------------

    @Test
    @DisplayName("Opened loan is listed among the recent loans")
    void openedLoanIsListed() {
        LoanJson loan = loanSteps.openLoan(aLoan());

        // other runs may open loans on the same stand at the same time, so look in a wider window
        assertThat(loanSteps.recentLoans(100)).extracting(LoanJson::id).contains(loan.id());
    }

    @Test
    @DisplayName("Payment history shows an applied payment and a payment to the paid-off loan (F-01)")
    @Tag("F-01")
    void paymentHistoryShowsBothResults() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        String payoff = uniquePaymentId();
        String late = uniquePaymentId();
        paymentSteps.pay(loan, payoff, 300);
        loansApi.postPayment(new PaymentRequest(late, loan.id(), 1_000))
                .then().statusCode(202);
        paymentSteps.waitForQueuedPayments();

        loansApi.getPayments(loan.id())
                .then().statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/payment-history.json"));
        assertThat(loanSteps.paymentHistory(loan).payments())
                .extracting(PaymentJson::paymentId, PaymentJson::result)
                .containsExactly(tuple(payoff, "APPLIED"), tuple(late, "LOAN_ALREADY_PAID_OFF"));
    }

    /** The refused values are above; these are the ends of the range the API promises to accept. */
    @DisplayName("Loans list accepts the ends of the allowed range")
    @ParameterizedTest(name = "limit={0} -> 200, no more than {0} loans")
    @ValueSource(ints = {1, 100})
    @Tag("readonly")
    void loansListAcceptsTheEndsOfTheRange(int limit) {
        List<LoanJson> loans = loanSteps.recentLoans(limit);

        assertThat(loans)
                .as("a stand may hold fewer loans than asked for, but never more")
                .hasSizeLessThanOrEqualTo(limit);
    }
}
