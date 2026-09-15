package lab.loans.api;

import lab.loans.store.ProcessedPayment;

/** One entry of GET /loans/{id}/payments. The time is an ISO-8601 string, like unlockedUntil in LoanView. */
public record PaymentView(String paymentId, long amount, String result, String processedAt) {

    static PaymentView of(ProcessedPayment payment) {
        return new PaymentView(payment.paymentId(), payment.amount(), payment.result(), payment.processedAt().toString());
    }
}
