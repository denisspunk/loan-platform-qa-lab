package lab.qa.tests.unit;

import lab.loans.domain.Loan;
import lab.loans.domain.UnlockDecision;
import lab.loans.domain.UnlockPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class UnlockPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    private final UnlockPolicy policy = new UnlockPolicy();

    @ParameterizedTest(name = "credit {0} + payment {1} at 100 KES/day -> {2} day(s), credit {3}")
    @CsvSource({
            "0,   99, 0, 99",
            "0,  100, 1,  0",
            "0,  101, 1,  1",
            "0,  150, 1, 50",
            "50,  50, 1,  0",
            "0,  250, 2, 50",
    })
    void everyFullDailyRateBuysOneDay(long credit, long amount, long expectedDays, long expectedCredit) {
        Loan loan = loanWithCredit(credit);

        UnlockDecision decision = policy.decide(loan, amount, NOW);

        assertThat(decision.days()).isEqualTo(expectedDays);
        assertThat(decision.newCredit()).isEqualTo(expectedCredit);
    }

    @Test
    void lessThanOneDayKeepsThePhoneAsItIs() {
        UnlockDecision decision = policy.decide(loanWithCredit(0), 60, NOW);

        assertThat(decision.action()).isEqualTo(UnlockDecision.Action.KEEP);
        assertThat(decision.unlockedUntil()).isNull();
    }

    @Test
    void lockedPhoneIsUnlockedFromNow() {
        UnlockDecision decision = policy.decide(loanWithCredit(0), 200, NOW);

        assertThat(decision.action()).isEqualTo(UnlockDecision.Action.UNLOCK);
        assertThat(decision.unlockedUntil()).isEqualTo(NOW.plus(Duration.ofDays(2)));
    }

    @Test
    void newDaysAreAddedOnTopOfAnUnlockedPhone() {
        Loan loan = loanWithCredit(0);
        loan.apply(100, UnlockDecision.unlock(1, 0, NOW.plus(Duration.ofDays(1))));

        UnlockDecision decision = policy.decide(loan, 100, NOW.plus(Duration.ofHours(2)));

        assertThat(decision.unlockedUntil()).isEqualTo(NOW.plus(Duration.ofDays(2)));
    }

    @Test
    void paymentThatCoversThePriceReleasesThePhone() {
        Loan loan = new Loan("LN-1", "350000000000001", 300, 100);

        UnlockDecision decision = policy.decide(loan, 300, NOW);

        assertThat(decision.action()).isEqualTo(UnlockDecision.Action.RELEASE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100})
    void nonPositiveAmountIsRejected(long amount) {
        assertThatThrownBy(() -> policy.decide(loanWithCredit(0), amount, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");
    }

    private static Loan loanWithCredit(long credit) {
        Loan loan = new Loan("LN-1", "350000000000001", 12_000, 100);
        if (credit > 0) {
            loan.apply(credit, UnlockDecision.keep(credit));
        }
        return loan;
    }
}
