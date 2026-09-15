package lab.qa.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** GET /loans/{id}/payments as the test framework sees it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentHistoryJson(String loanId, List<PaymentJson> payments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentJson(String paymentId, long amount, String result, String processedAt) {
    }
}
