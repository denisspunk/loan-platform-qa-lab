package lab.loans.domain;

import java.time.Instant;

/** What a payment does to the phone. Produced by UnlockPolicy, applied by PaymentProcessor. */
public record UnlockDecision(Action action, long days, long newCredit, Instant unlockedUntil) {

    public enum Action {
        /** Enough money for at least one day: unlock and schedule the relock. */
        UNLOCK,
        /** Less than one day's rate: keep the money as credit, touch nothing. */
        KEEP,
        /** The price is covered: remove the lock for good. */
        RELEASE
    }

    public static UnlockDecision unlock(long days, long newCredit, Instant unlockedUntil) {
        return new UnlockDecision(Action.UNLOCK, days, newCredit, unlockedUntil);
    }

    public static UnlockDecision keep(long newCredit) {
        return new UnlockDecision(Action.KEEP, 0, newCredit, null);
    }

    public static UnlockDecision release() {
        return new UnlockDecision(Action.RELEASE, 0, 0, null);
    }
}
