package lab.loans.api;

/** The M-Pesa style callback body: who paid, for which loan, how many KES. */
public record PaymentRequest(String paymentId, String loanId, long amount) {
}
