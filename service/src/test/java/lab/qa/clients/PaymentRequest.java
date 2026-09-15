package lab.qa.clients;

/** Body of POST /payments, as the test framework sees it. */
public record PaymentRequest(String paymentId, String loanId, long amount) {
}
