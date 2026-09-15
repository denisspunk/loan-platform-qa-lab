package lab.qa.dsl;

import lab.qa.clients.LoanJson;
import lab.qa.clients.LoansApi;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.Config;

import java.time.Duration;

import static lab.qa.data.TestData.uniquePaymentId;
import static org.awaitility.Awaitility.await;

/**
 * Payments arrive as M-Pesa callbacks and are applied asynchronously.
 * Every step here waits until the platform has processed what it sent, so tests never sleep.
 * Steps wait; the business checks stay in the tests.
 */
public class PaymentSteps {

    private final LoansApi api;
    private final LoanSteps loanSteps;

    public PaymentSteps(LoansApi api, LoanSteps loanSteps) {
        this.api = api;
        this.loanSteps = loanSteps;
    }

    /** Pays with a fresh payment id and returns the loan after the payment has been applied. */
    public LoanJson pay(LoanJson loan, long amount) {
        return pay(loan, uniquePaymentId(), amount);
    }

    /** Pays with the given payment id, for tests that check the id later, e.g. at the partner. */
    public LoanJson pay(LoanJson loan, String paymentId, long amount) {
        long paidBefore = loanSteps.current(loan).paid();
        send(loan, paymentId, amount);
        return waitUntilPaidAtLeast(loan, paidBefore + amount);
    }

    private void send(LoanJson loan, String paymentId, long amount) {
        api.postPayment(new PaymentRequest(paymentId, loan.id(), amount))
                .then().statusCode(202);
    }

    private LoanJson waitUntilPaidAtLeast(LoanJson loan, long expectedPaid) {
        return await("payments applied to " + loan.id())
                .atMost(Config.ASYNC_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> loanSteps.current(loan), current -> current.paid() >= expectedPaid);
    }
}
