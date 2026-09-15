package lab.loans.domain;

/** What GET /loans/{id} returns. A record: immutable, with equals/hashCode/toString for free. */
public record LoanView(
        String id,
        String deviceId,
        long price,
        long dailyRate,
        long paid,
        long balance,
        long credit,
        LoanStatus status,
        DeviceState deviceState,
        String unlockedUntil) {
}
