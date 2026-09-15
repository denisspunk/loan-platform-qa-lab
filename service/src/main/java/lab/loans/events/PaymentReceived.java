package lab.loans.events;

import java.time.Instant;

/** The event the payments squad publishes to the "payments.received" topic. */
public record PaymentReceived(String paymentId, String loanId, long amount, Instant receivedAt) {
}
