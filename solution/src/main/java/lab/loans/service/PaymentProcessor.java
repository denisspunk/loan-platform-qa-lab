package lab.loans.service;

import lab.loans.Bugs;
import lab.loans.domain.Loan;
import lab.loans.domain.LoanStatus;
import lab.loans.domain.UnlockDecision;
import lab.loans.domain.UnlockPolicy;
import lab.loans.events.PaymentReceived;
import lab.loans.knox.DeviceLockClient;
import lab.loans.store.LoanRepository;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The core-platform chain: payment -> loan -> device -> relock schedule.
 * Consumes "payments.received". Idempotent by paymentId, because the broker delivers at least once.
 */
public class PaymentProcessor {

    public static final String TOPIC = "payments.received";

    private final LoanRepository loans;
    private final DeviceLockClient deviceLock;
    private final Clock clock;
    private final UnlockPolicy policy = new UnlockPolicy();
    private final Set<String> processedPaymentIds = ConcurrentHashMap.newKeySet();

    public PaymentProcessor(LoanRepository loans, DeviceLockClient deviceLock, Clock clock) {
        this.loans = loans;
        this.deviceLock = deviceLock;
        this.clock = clock;
    }

    /** Entry point for the event bus, which hands over any payload. */
    public void handle(Object event) {
        if (!(event instanceof PaymentReceived payment)) {
            throw new IllegalArgumentException("not a PaymentReceived: " + event);
        }
        process(payment);
    }

    public ProcessingResult process(PaymentReceived payment) {
        if (!Bugs.DOUBLE_PROCESSING.isOn() && processedPaymentIds.contains(payment.paymentId())) {
            return ProcessingResult.DUPLICATE;
        }

        Loan loan = loans.findById(payment.loanId())
                .orElseThrow(() -> new UnknownLoanException(payment.loanId()));
        if (loan.status() == LoanStatus.PAID_OFF) {
            processedPaymentIds.add(payment.paymentId());
            return ProcessingResult.LOAN_ALREADY_PAID_OFF;
        }

        UnlockDecision decision = policy.decide(loan, payment.amount(), clock.instant());
        switch (decision.action()) {
            case UNLOCK -> {
                deviceLock.unlock(loan.deviceId(), payment.paymentId());
                deviceLock.scheduleRelock(loan.deviceId(), decision.unlockedUntil(), payment.paymentId());
            }
            case RELEASE -> deviceLock.release(loan.deviceId(), payment.paymentId());
            case KEEP -> {
                // partial payment: nothing to tell the partner
            }
        }

        loan.apply(payment.amount(), decision);
        processedPaymentIds.add(payment.paymentId());
        return ProcessingResult.APPLIED;
    }
}
