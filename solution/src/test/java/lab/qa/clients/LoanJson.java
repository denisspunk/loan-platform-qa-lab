package lab.qa.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GET /loans/{id} as the test framework sees it. Deliberately not the service's own LoanView:
 * black-box tests describe the API from the outside, so a rename in the service breaks a test.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanJson(
        String id,
        String deviceId,
        long price,
        long dailyRate,
        long paid,
        long balance,
        long credit,
        String status,
        String deviceState,
        String unlockedUntil) {
}
