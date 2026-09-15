package lab.qa.tests.integration;

import lab.qa.clients.LoanJson;
import lab.qa.clients.PaymentHistoryJson;
import lab.qa.clients.PaymentHistoryJson.PaymentJson;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.IntStream;

import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniquePaymentId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.equalTo;

/** What the web UI reads: the list of recent loans and the payment history of a loan. */
@Tag("integration")
@DisplayName("Loans list and payment history: what the web UI reads")
class LoanHistoryApiTest extends BaseIT {

    // --- recent loans -------------------------------------------------------------------------------

    @Test
    @DisplayName("Loans list shows the newest loan first")
    void loansListShowsTheNewestLoanFirst() {
        LoanJson older = loanSteps.openLoan(aLoan());
        LoanJson newer = loanSteps.openLoan(aLoan());

        List<LoanJson> recent = loanSteps.recentLoans(2);

        assertThat(recent).extracting(LoanJson::id).containsExactly(newer.id(), older.id());
    }

    @Test
    @DisplayName("Loans list returns 20 loans when no limit is given")
    void loansListReturnsTwentyByDefault() {
        IntStream.range(0, 21).forEach(i -> loanSteps.openLoan(aLoan()));

        loansApi.listLoans()
                .then().statusCode(200)
                .body("loans.size()", equalTo(20));
    }

    @Test
    @DisplayName("Listed loan shows its current state")
    void listedLoanShowsItsCurrentState() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        LoanJson paid = paymentSteps.pay(loan, 150);

        List<LoanJson> recent = loanSteps.recentLoans(5);

        assertThat(recent).filteredOn(listed -> listed.id().equals(loan.id())).containsExactly(paid);
    }

    @DisplayName("Loans list refuses a limit that is not a number from 1 to 100")
    @ParameterizedTest(name = "limit={0} -> 400")
    @ValueSource(strings = {"0", "101", "-1", "abc", ""})
    void loansListRefusesAnInvalidLimit(String limit) {
        loansApi.listLoans(limit)
                .then().statusCode(400)
                .body("error", equalTo("limit must be a number from 1 to 100"));
    }

    // --- payment history ----------------------------------------------------------------------------

    @Test
    @DisplayName("New loan has an empty payment history")
    void newLoanHasAnEmptyHistory() {
        LoanJson loan = loanSteps.openLoan(aLoan());

        PaymentHistoryJson history = loanSteps.paymentHistory(loan);

        assertSoftly(softly -> {
            softly.assertThat(history.loanId()).isEqualTo(loan.id());
            softly.assertThat(history.payments()).isEmpty();
        });
    }

    @Test
    @DisplayName("Payment history lists applied payments in the order they were processed")
    void historyListsAppliedPaymentsInOrder() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        String first = uniquePaymentId();
        String second = uniquePaymentId();
        paymentSteps.pay(loan, first, 150);
        paymentSteps.pay(loan, second, 50);
        paymentSteps.waitForQueuedPayments();

        PaymentHistoryJson history = loanSteps.paymentHistory(loan);

        assertThat(history.payments())
                .extracting(PaymentJson::paymentId, PaymentJson::amount, PaymentJson::result, PaymentJson::processedAt)
                .containsExactly(
                        tuple(first, 150L, "APPLIED", clock.instant().toString()),
                        tuple(second, 50L, "APPLIED", clock.instant().toString()));
    }

    @Test
    @DisplayName("Payment delivered twice appears in the history once")
    void duplicatePaymentAppearsOnce() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        paymentSteps.payTwiceWithSameId(loan, 500);
        paymentSteps.waitForQueuedPayments();

        PaymentHistoryJson history = loanSteps.paymentHistory(loan);

        assertThat(history.payments())
                .as("the duplicated payment and the 1 KES marker sent after it")
                .extracting(PaymentJson::amount, PaymentJson::result)
                .containsExactly(tuple(500L, "APPLIED"), tuple(1L, "APPLIED"));
    }

    @Test
    @DisplayName("Payment to a paid-off loan is recorded as LOAN_ALREADY_PAID_OFF (F-01)")
    void paymentToAPaidOffLoanIsRecordedAsAlreadyPaidOff() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        String payoff = uniquePaymentId();
        String late = uniquePaymentId();
        paymentSteps.pay(loan, payoff, 300);
        loansApi.postPayment(new PaymentRequest(late, loan.id(), 1_000))
                .then().statusCode(202);
        paymentSteps.waitForQueuedPayments();

        PaymentHistoryJson history = loanSteps.paymentHistory(loan);

        assertThat(history.payments())
                .extracting(PaymentJson::paymentId, PaymentJson::amount, PaymentJson::result)
                .containsExactly(
                        tuple(payoff, 300L, "APPLIED"),
                        tuple(late, 1_000L, "LOAN_ALREADY_PAID_OFF"));
    }

    @Test
    @DisplayName("Payment history of an unknown loan is not found")
    void historyOfAnUnknownLoanIsNotFound() {
        loansApi.getPayments("LN-missing")
                .then().statusCode(404)
                .body("error", equalTo("loan LN-missing not found"));
    }
}
