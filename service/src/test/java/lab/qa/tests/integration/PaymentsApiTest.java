package lab.qa.tests.integration;

import lab.qa.clients.LoanJson;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniquePaymentId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@Tag("integration")
@DisplayName("Payments API: HTTP in, event bus, processor, partner out")
class PaymentsApiTest extends BaseIT {

    // --- the payment request itself -----------------------------------------------------------

    @Test
    @DisplayName("Accepted payment is answered with 202 and its own payment id")
    void acceptedPaymentIsAnsweredWithItsId() {
        LoanJson loan = loanSteps.openLoan(aLoan());
        String paymentId = uniquePaymentId();

        loansApi.postPayment(new PaymentRequest(paymentId, loan.id(), 100))
                .then().statusCode(202)
                .body("paymentId", equalTo(paymentId))
                .body("status", equalTo("ACCEPTED"));
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

    @DisplayName("Payment without a required field is rejected")
    @ParameterizedTest(name = "{2} -> 400 \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            {"loanId": "LN-any", "amount": 100}                   | paymentId is required | no paymentId
            {"paymentId": " ", "loanId": "LN-any", "amount": 100} | paymentId is required | blank paymentId
            {"paymentId": "MPESA-X", "amount": 100}               | loanId is required    | no loanId
            """)
    void paymentWithoutARequiredFieldIsRejected(String body, String error, String caseName) {
        loansApi.postPaymentBody(body)
                .then().statusCode(400)
                .body("error", equalTo(error));
    }

    @Test
    @DisplayName("Payment body that is not JSON is rejected")
    void paymentBodyThatIsNotJsonIsRejected() {
        loansApi.postPaymentBody("{not json")
                .then().statusCode(400)
                .body("error", startsWith("invalid JSON"));
    }

    @Test
    @DisplayName("Payment for an unknown loan is not found")
    void paymentForUnknownLoanIsNotFound() {
        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), "LN-404", 100))
                .then().statusCode(404)
                .body("error", equalTo("loan LN-404 not found"));
    }

    // --- what a payment does, through the event bus and the partner -----------------------------

    @Test
    @DisplayName("Accepted payment unlocks the phone through the partner")
    void acceptedPaymentUnlocksThePhoneThroughThePartner() {
        Instant now = clock.instant();
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        String paymentId = uniquePaymentId();

        LoanJson paid = paymentSteps.pay(loan, paymentId, 300);

        // 300 KES at 100 KES/day buys 3 days, counted from the test clock
        String expectedUntil = now.plus(Duration.ofDays(3)).toString();
        assertSoftly(softly -> {
            softly.assertThat(paid.deviceState()).isEqualTo("UNLOCKED");
            softly.assertThat(paid.unlockedUntil()).isEqualTo(expectedUntil);
        });
        assertThat(knox.callsFor(loan.deviceId(), "relock"))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.getHeader("X-Correlation-Id")).isEqualTo(paymentId);
                    assertThat(call.getBodyAsString()).contains(expectedUntil);
                });
    }

    @Test
    @DisplayName("Partial payment keeps the phone locked and does not call the partner")
    void partialPaymentKeepsThePhoneLocked() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson paid = paymentSteps.pay(loan, 60);

        assertSoftly(softly -> {
            softly.assertThat(paid.deviceState()).isEqualTo("LOCKED");
            softly.assertThat(paid.credit()).isEqualTo(60);
            softly.assertThat(paid.unlockedUntil()).isNull();
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).isEmpty();
            softly.assertThat(knox.callsFor(loan.deviceId(), "relock")).isEmpty();
            softly.assertThat(knox.callsFor(loan.deviceId(), "release")).isEmpty();
        });
    }

    @Test
    @DisplayName("Payment that covers the price releases the phone for good")
    void paymentThatCoversThePriceReleasesThePhone() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        String paymentId = uniquePaymentId();

        LoanJson paid = paymentSteps.pay(loan, paymentId, 300);

        assertSoftly(softly -> {
            softly.assertThat(paid.status()).isEqualTo("PAID_OFF");
            softly.assertThat(paid.deviceState()).isEqualTo("RELEASED");
            softly.assertThat(paid.balance()).isZero();
            softly.assertThat(paid.unlockedUntil()).isNull();
            softly.assertThat(knox.callsFor(loan.deviceId(), "release"))
                    .singleElement()
                    .satisfies(call -> assertThat(call.getHeader("X-Correlation-Id")).isEqualTo(paymentId));
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).isEmpty();
            softly.assertThat(knox.callsFor(loan.deviceId(), "relock")).isEmpty();
        });
    }

    @Test
    @DisplayName("Redelivered callback is applied once")
    void duplicateCallbackIsAppliedOnce() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson after = paymentSteps.payTwiceWithSameId(loan, 500);

        assertSoftly(softly -> {
            softly.assertThat(after.paid()).isEqualTo(501);
            softly.assertThat(after.credit()).isEqualTo(1);
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).hasSize(1);
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
        paymentSteps.waitForQueuedPayments();

        LoanJson after = loanSteps.current(loan);
        assertSoftly(softly -> {
            softly.assertThat(after.paid()).isEqualTo(300);
            softly.assertThat(after.status()).isEqualTo("PAID_OFF");
            softly.assertThat(knox.callsFor(loan.deviceId(), "release")).as("only the payoff released the phone").hasSize(1);
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).isEmpty();
        });
    }

    // --- when the partner fails -----------------------------------------------------------------

    @Test
    @DisplayName("Partner failure sends the payment to dead letters and leaves the loan unchanged (F-04)")
    @Tag("F-04")
    void partnerFailureLeavesTheLoanUnchanged() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        knox.respondWith(loan.deviceId(), "relock", 503);

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loan.id(), 300))
                .then().statusCode(202);
        paymentSteps.waitForQueuedPayments();

        LoanJson after = loanSteps.current(loan);
        assertSoftly(softly -> {
            softly.assertThat(after.paid()).isZero();
            softly.assertThat(after.deviceState()).isEqualTo("LOCKED");
            softly.assertThat(app.deadLetters()).anySatisfy(letter -> {
                assertThat(letter.key()).isEqualTo(loan.id());
                assertThat(letter.error()).contains("relock returned 503");
            });
        });
    }

    @Test
    @Disabled("F-04: the service unlocks the phone before the relock is scheduled; if relock fails, the phone stays unlocked")
    @DisplayName("Phone is not unlocked when the relock cannot be scheduled")
    @Tag("F-04")
    void phoneIsNotUnlockedWhenTheRelockFails() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        knox.respondWith(loan.deviceId(), "relock", 503);

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loan.id(), 300))
                .then().statusCode(202);
        paymentSteps.waitForQueuedPayments();

        assertThat(knox.callsFor(loan.deviceId(), "unlock")).isEmpty();
    }
}
