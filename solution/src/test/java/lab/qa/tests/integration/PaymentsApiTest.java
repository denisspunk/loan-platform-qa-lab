package lab.qa.tests.integration;

import lab.qa.clients.LoanJson;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.BaseIT;
import lab.qa.core.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniquePaymentId;
import static lab.qa.dsl.LoanAssert.assertThatLoan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/** The running service plus the partner stub: HTTP in, event bus, consumer, HTTP out. */
@Tag("integration")
class PaymentsApiTest extends BaseIT {

    @Test
    void newLoanStartsWithALockedPhone() {
        loansApi.createLoan(aLoan().price(12_000).dailyRate(100).build())
                .then()
                .statusCode(201)
                .body("id", startsWith("LN-"))
                .body("deviceState", equalTo("LOCKED"))
                .body("balance", equalTo(12_000));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountIsRejected(long amount) {
        LoanJson loan = loanSteps.openLoan(aLoan());

        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), loan.id(), amount))
                .then()
                .statusCode(400)
                .body("error", containsString("amount"));
    }

    @Test
    void paymentForUnknownLoanIsNotFound() {
        loansApi.postPayment(new PaymentRequest(uniquePaymentId(), "LN-404", 100))
                .then()
                .statusCode(404);
    }

    @Test
    void acceptedPaymentUnlocksThePhoneThroughThePartner() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        String paymentId = uniquePaymentId();

        loansApi.postPayment(new PaymentRequest(paymentId, loan.id(), 300))
                .then()
                .statusCode(202)
                .body("status", equalTo("ACCEPTED"));

        await().atMost(Config.ASYNC_TIMEOUT).untilAsserted(() ->
                loansApi.getLoan(loan.id())
                        .then()
                        .body("deviceState", equalTo("UNLOCKED"))
                        .body("unlockedUntil", equalTo("2026-09-18T09:00:00Z")));

        assertThat(knox.callsFor(loan.deviceId(), "relock"))
                .singleElement()
                .satisfies(call -> assertThat(call.getHeader("X-Correlation-Id")).isEqualTo(paymentId));
    }

    @Test
    void duplicateCallbackIsAppliedOnce() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson after = paymentSteps.payTwiceWithSameId(loan, 500);

        assertThatLoan(after).hasPaid(501).hasCredit(1);
        deviceSteps.shouldHaveUnlocked(loan, 1);
    }
}
