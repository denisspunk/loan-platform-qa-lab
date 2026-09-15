package lab.loans.domain;

import lab.loans.Bugs;

import java.time.Duration;
import java.time.Instant;

/**
 * Pay-as-you-go rule: every full dailyRate buys one day of an unlocked phone.
 * The remainder stays on the loan as credit and counts towards the next payment.
 * Amounts are whole Kenyan shillings (KES), the way M-Pesa sends them.
 */
public class UnlockPolicy {

    private static final Duration ONE_DAY = Duration.ofDays(1);

    public UnlockDecision decide(Loan loan, long amount, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (loan.paid() + amount >= loan.price()) {
            return UnlockDecision.release();
        }

        long available = loan.credit() + amount;
        long days;
        long newCredit;
        if (Bugs.ROUNDING_UP.isOn()) {
            days = (available + loan.dailyRate() - 1) / loan.dailyRate();
            newCredit = 0;
        } else {
            days = available / loan.dailyRate();
            newCredit = available % loan.dailyRate();
        }

        if (days == 0) {
            return UnlockDecision.keep(newCredit);
        }

        // A phone that is still unlocked gets the new days on top of the old ones.
        Instant from = loan.unlockedUntil() != null && loan.unlockedUntil().isAfter(now)
                ? loan.unlockedUntil()
                : now;
        return UnlockDecision.unlock(days, newCredit, from.plus(ONE_DAY.multipliedBy(days)));
    }
}
