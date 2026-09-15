package lab.loans.domain;

import java.time.Instant;

/** A phone loan. Mutable on purpose: payments change it, so its methods are synchronized. */
public class Loan {

    private final String id;
    private final String deviceId;
    private final long price;
    private final long dailyRate;

    private long paid;
    private long credit;
    private LoanStatus status = LoanStatus.ACTIVE;
    private DeviceState deviceState = DeviceState.LOCKED;
    private Instant unlockedUntil;

    public Loan(String id, String deviceId, long price, long dailyRate) {
        if (price <= 0) {
            throw new IllegalArgumentException("price must be positive, was " + price);
        }
        if (dailyRate <= 0) {
            throw new IllegalArgumentException("dailyRate must be positive, was " + dailyRate);
        }
        this.id = id;
        this.deviceId = deviceId;
        this.price = price;
        this.dailyRate = dailyRate;
    }

    public String id() { return id; }
    public String deviceId() { return deviceId; }
    public long price() { return price; }
    public long dailyRate() { return dailyRate; }
    public synchronized long paid() { return paid; }
    public synchronized long credit() { return credit; }
    public synchronized LoanStatus status() { return status; }
    public synchronized Instant unlockedUntil() { return unlockedUntil; }

    public synchronized void apply(long amount, UnlockDecision decision) {
        paid += amount;
        credit = decision.newCredit();
        switch (decision.action()) {
            case UNLOCK -> {
                deviceState = DeviceState.UNLOCKED;
                unlockedUntil = decision.unlockedUntil();
            }
            case RELEASE -> {
                status = LoanStatus.PAID_OFF;
                deviceState = DeviceState.RELEASED;
                unlockedUntil = null;
            }
            case KEEP -> {
                // partial payment: only the credit changes
            }
        }
    }

    /** The phone relocks by itself once unlockedUntil has passed, so the state depends on "now". */
    public synchronized DeviceState deviceStateAt(Instant now) {
        if (deviceState == DeviceState.UNLOCKED && !now.isBefore(unlockedUntil)) {
            return DeviceState.LOCKED;
        }
        return deviceState;
    }

    public synchronized LoanView view(Instant now) {
        return new LoanView(id, deviceId, price, dailyRate, paid, Math.max(0, price - paid), credit,
                status, deviceStateAt(now), unlockedUntil == null ? null : unlockedUntil.toString());
    }
}
