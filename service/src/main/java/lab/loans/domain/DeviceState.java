package lab.loans.domain;

public enum DeviceState {
    /** Partner keeps the phone locked: nothing works except the lender's app. */
    LOCKED,
    /** Paid up to unlockedUntil; Partner relocks it at that moment. */
    UNLOCKED,
    /** Loan paid off: the lock is removed for good. */
    RELEASED
}
