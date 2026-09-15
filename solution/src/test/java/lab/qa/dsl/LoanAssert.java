package lab.qa.dsl;

import lab.qa.clients.LoanJson;
import org.assertj.core.api.AbstractAssert;

/**
 * Domain checks in the words of the business:
 * assertThatLoan(loan).hasDeviceState("UNLOCKED").isUnlockedUntil("2026-09-16T09:00:00Z");
 */
public class LoanAssert extends AbstractAssert<LoanAssert, LoanJson> {

    private LoanAssert(LoanJson actual) {
        super(actual, LoanAssert.class);
    }

    public static LoanAssert assertThatLoan(LoanJson actual) {
        return new LoanAssert(actual);
    }

    public LoanAssert hasStatus(String expected) {
        isNotNull();
        if (!expected.equals(actual.status())) {
            failWithMessage("Expected loan %s to have status %s but was %s", actual.id(), expected, actual.status());
        }
        return this;
    }

    public LoanAssert hasDeviceState(String expected) {
        isNotNull();
        if (!expected.equals(actual.deviceState())) {
            failWithMessage("Expected the phone on loan %s to be %s but was %s",
                    actual.id(), expected, actual.deviceState());
        }
        return this;
    }

    public LoanAssert isUnlockedUntil(String isoInstant) {
        isNotNull();
        if (!isoInstant.equals(actual.unlockedUntil())) {
            failWithMessage("Expected loan %s to be unlocked until %s but was %s",
                    actual.id(), isoInstant, actual.unlockedUntil());
        }
        return this;
    }

    public LoanAssert hasPaid(long expected) {
        isNotNull();
        if (actual.paid() != expected) {
            failWithMessage("Expected loan %s to have %s KES paid but was %s", actual.id(), expected, actual.paid());
        }
        return this;
    }

    public LoanAssert hasCredit(long expected) {
        isNotNull();
        if (actual.credit() != expected) {
            failWithMessage("Expected loan %s to have %s KES credit but was %s", actual.id(), expected, actual.credit());
        }
        return this;
    }

    public LoanAssert hasBalance(long expected) {
        isNotNull();
        if (actual.balance() != expected) {
            failWithMessage("Expected loan %s to have a balance of %s KES but was %s",
                    actual.id(), expected, actual.balance());
        }
        return this;
    }
}
