package lab.loans;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fault injection: seeded bugs that show which level of the test pyramid catches which regression.
 * Off by default; turn one on with -Dlab.bugs=ROUNDING_UP (comma-separated for several).
 */
public enum Bugs {
    /** UnlockPolicy rounds days up: 150 KES at 100 KES/day unlocks the phone for 2 days instead of 1. */
    ROUNDING_UP,
    /** PaymentProcessor forgets processed payment ids: a redelivered event is applied twice. */
    DOUBLE_PROCESSING,
    /** HttpDeviceLockClient sends relockAt as epoch millis instead of an ISO-8601 string. */
    KNOX_EPOCH_DATE,
    /** POST /payments accepts amount 0 instead of rejecting it with 400. */
    ZERO_AMOUNT_ACCEPTED;

    public boolean isOn() {
        return enabled().contains(this);
    }

    private static Set<Bugs> enabled() {
        String raw = System.getProperty("lab.bugs", "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(Bugs::valueOf)
                .collect(Collectors.toSet());
    }
}
