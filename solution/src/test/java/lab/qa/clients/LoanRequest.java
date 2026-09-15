package lab.qa.clients;

/** Body of POST /loans, as the test framework sees it. */
public record LoanRequest(String deviceId, long price, long dailyRate) {
}
