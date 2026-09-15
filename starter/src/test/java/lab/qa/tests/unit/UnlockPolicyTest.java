package lab.qa.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.loans.domain.Loan;
import lab.loans.domain.UnlockDecision;
import lab.loans.domain.UnlockPolicy;

@Tag("unit")
class UnlockPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    private final UnlockPolicy policy = new UnlockPolicy();

    @ParameterizedTest(name = "credit {0} + paid {1} KES at 100 KES/day -> {2} day(s), credit {3}")
    @CsvSource({
            " 0,   1, 0,  1",
            " 0,  99, 0, 99",
            " 0, 100, 1,  0",
            " 0, 101, 1,  1",
            " 0, 150, 1, 50",
            " 0, 250, 2, 50",
            "50,  50, 1,  0",
            "30,  50, 0, 80",
    })
    void everyFullDailyRateBuysOneDay(long credit, long amount, long expectedDays, long expectedCredit) {

        Loan loan = loanWithCredit(credit);
        UnlockDecision decision = policy.decide(loan, amount, NOW);

        assertSoftly(softly -> {
            softly.assertThat(decision.days()).isEqualTo(expectedDays);
            softly.assertThat(decision.newCredit()).isEqualTo(expectedCredit);
        });
    }

    @ParameterizedTest(name = "Paid {0} KES is rejected")
    @ValueSource(longs = { 0, -100 })
    void nonPositiveAmountIsRejected(long amount) {
        Loan loan = new Loan("LN-1", "350000000000001", 12_000, 100);

        assertThatThrownBy(() -> policy.decide(loan, amount, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    @DisplayName("Locked phone is unlocked from now")
    void lockedPhoneIsUnlockedFromNow() {
        Loan loan = loanWithCredit(0);

        UnlockDecision decision = policy.decide(loan, 200, NOW);

        assertThat(decision.action()).isEqualTo(UnlockDecision.Action.UNLOCK);
        assertThat(decision.unlockedUntil()).isEqualTo(NOW.plus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("New days are added on top of an unlocked phone")
    void newDaysAreAddedOnTopOfAnUnlockedPhone() {
        Loan loan = loanWithCredit(0);
        Instant paidUntil = NOW.plus(Duration.ofDays(1));
        loan.apply(100, UnlockDecision.unlock(1, 0, paidUntil));

        UnlockDecision decision = policy.decide(loan, 100, NOW);

        assertThat(decision.unlockedUntil()).isEqualTo(paidUntil.plus(Duration.ofDays(1)));
    }

    @ParameterizedTest(name = "price 300, paid {0} KES -> {1}")
    @CsvSource({
            "299, UNLOCK",
            "300, RELEASE",
            "350, RELEASE",
    })
    void paymentThatCoversThePriceReleasesThePhone(long amount, UnlockDecision.Action expectedAction) {
        Loan loan = new Loan("LN-1", "350000000000001", 300, 100);

        UnlockDecision decision = policy.decide(loan, amount, NOW);

        assertThat(decision.action()).isEqualTo(expectedAction);
    }

    @Test
    void lessThanOneDayKeepsThePhoneAsItIs() {
        UnlockDecision decision = policy.decide(loanWithCredit(0), 60, NOW);

        assertThat(decision.action()).isEqualTo(UnlockDecision.Action.KEEP);
        assertThat(decision.unlockedUntil()).isNull();
    }

    @Test
    void expiredUnlockCountsNewDaysFromNow() {
        Loan loan = loanWithCredit(0);
        Instant expiredAt = NOW.minus(Duration.ofDays(3));
        loan.apply(100, UnlockDecision.unlock(1, 0, expiredAt));

        UnlockDecision decision = policy.decide(loan, 100, NOW);

        assertThat(decision.unlockedUntil()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    private static Loan loanWithCredit(long credit) {
        Loan loan = new Loan("LN-1", "350000000000001", 12_000, 100);
        if (credit > 0) {
            loan.apply(credit, UnlockDecision.keep(credit));
        }
        return loan;
    }

}
