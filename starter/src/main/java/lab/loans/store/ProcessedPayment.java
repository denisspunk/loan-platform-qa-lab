package lab.loans.store;

import java.time.Instant;

/** A payment the processor has handled. result is a ProcessingResult name, e.g. APPLIED or LOAN_ALREADY_PAID_OFF. */
public record ProcessedPayment(String paymentId, String loanId, long amount, String result, Instant processedAt) {
}
