package lab.qa.dsl;

import lab.qa.clients.LoanJson;
import lab.qa.clients.LoansApi;
import lab.qa.clients.PaymentRequest;
import lab.qa.core.Config;

import java.time.Duration;

import static lab.qa.data.TestData.aLoan;
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

    /**
     * At-least-once delivery: the same callback arrives twice.
     * A 1 KES marker payment goes right after both copies; once the marker is applied,
     * both copies have been processed too, and only then is a balance check honest.
     */
    public LoanJson payTwiceWithSameId(LoanJson loan, long amount) {
        long paidBefore = loanSteps.current(loan).paid();
        String paymentId = uniquePaymentId();
        send(loan, paymentId, amount);
        send(loan, paymentId, amount);
        send(loan, uniquePaymentId(), 1);
        return waitUntilPaidAtLeast(loan, paidBefore + amount + 1);
    }

    /**
     * Waits until every payment sent so far has been processed, including payments that change nothing
     * on their loan: a paid-off loan, a failing partner. The in-memory event bus handles events one at a time
     * and in order, so a 1 KES payment to a fresh marker loan is applied only after all of them.
     * If the bus ever processes events in parallel, this step stops being a guarantee.
     */
    public void waitForQueuedPayments() {
        LoanJson marker = loanSteps.openLoan(aLoan());
        pay(marker, 1);
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
