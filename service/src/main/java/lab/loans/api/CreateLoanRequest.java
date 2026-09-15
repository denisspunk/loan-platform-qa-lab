package lab.loans.api;

public record CreateLoanRequest(String deviceId, long price, long dailyRate) {
}
