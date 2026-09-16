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

import lab.loans.domain.DeviceState;
import lab.loans.domain.Loan;
import lab.loans.domain.LoanStatus;
import lab.loans.domain.LoanView;
import lab.loans.domain.UnlockDecision;

@Tag("unit")
@DisplayName("Loan: what a decision does to the loan and the phone")
class LoanTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");
    private static final long PRICE = 12_000;
    private static final long DAILY_RATE = 100;

    @Test
    @DisplayName("New loan owes the whole price and keeps the phone locked")
    void newLoanOwesTheWholePrice() {
        LoanView view = newLoan().view(NOW);

        assertSoftly(softly -> {
            softly.assertThat(view.paid()).isZero();
            softly.assertThat(view.balance()).isEqualTo(PRICE);
            softly.assertThat(view.credit()).isZero();
            softly.assertThat(view.status()).isEqualTo(LoanStatus.ACTIVE);
            softly.assertThat(view.deviceState()).isEqualTo(DeviceState.LOCKED);
            softly.assertThat(view.unlockedUntil()).isNull();
        });
    }

    @DisplayName("Price and daily rate must be positive")
    @ParameterizedTest(name = "price {0}, daily rate {1} -> {2}")
    @CsvSource({
            "    0, 100, price must be positive",
            " -100, 100, price must be positive",
            "12000,   0, dailyRate must be positive",
            "12000, -50, dailyRate must be positive",
    })
    void priceAndDailyRateMustBePositive(long price, long dailyRate, String message) {
        assertThatThrownBy(() -> new Loan("LN-1", "350000000000001", price, dailyRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    @Test
    @DisplayName("Unlock decision unlocks the phone until the given moment")
    void unlockDecisionUnlocksThePhone() {
        Loan loan = newLoan();
        Instant until = NOW.plus(Duration.ofDays(1));

        loan.apply(150, UnlockDecision.unlock(1, 50, until));

        assertSoftly(softly -> {
            softly.assertThat(loan.paid()).isEqualTo(150);
            softly.assertThat(loan.credit()).isEqualTo(50);
            softly.assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
            softly.assertThat(loan.deviceStateAt(NOW)).isEqualTo(DeviceState.UNLOCKED);
            softly.assertThat(loan.view(NOW).unlockedUntil()).isEqualTo(until.toString());
        });
    }

    @Test
    @DisplayName("Keep decision only adds the money: the phone stays locked")
    void keepDecisionOnlyAddsTheMoney() {
        Loan loan = newLoan();

        loan.apply(60, UnlockDecision.keep(60));

        assertSoftly(softly -> {
            softly.assertThat(loan.paid()).isEqualTo(60);
            softly.assertThat(loan.credit()).isEqualTo(60);
            softly.assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
            softly.assertThat(loan.deviceStateAt(NOW)).isEqualTo(DeviceState.LOCKED);
            softly.assertThat(loan.unlockedUntil()).isNull();
        });
    }

    @Test
    @DisplayName("Release decision pays the loan off and frees the phone for good")
    void releaseDecisionPaysTheLoanOff() {
        Loan loan = newLoan();
        loan.apply(150, UnlockDecision.unlock(1, 50, NOW.plus(Duration.ofDays(1))));

        loan.apply(PRICE - 150, UnlockDecision.release());

        assertSoftly(softly -> {
            softly.assertThat(loan.paid()).isEqualTo(PRICE);
            softly.assertThat(loan.credit()).isZero();
            softly.assertThat(loan.status()).isEqualTo(LoanStatus.PAID_OFF);
            softly.assertThat(loan.unlockedUntil()).isNull();
            softly.assertThat(loan.deviceStateAt(NOW.plus(Duration.ofDays(365)))).isEqualTo(DeviceState.RELEASED);
        });
    }

    @DisplayName("The phone relocks by itself exactly at unlockedUntil")
    @ParameterizedTest(name = "{0} s from unlockedUntil -> {1}")
    @CsvSource({
            "-1, UNLOCKED",
            " 0, LOCKED",
            " 1, LOCKED",
    })
    void phoneRelocksExactlyAtUnlockedUntil(long secondsFromUntil, DeviceState expected) {
        Loan loan = newLoan();
        Instant until = NOW.plus(Duration.ofDays(1));
        loan.apply(100, UnlockDecision.unlock(1, 0, until));

        assertThat(loan.deviceStateAt(until.plusSeconds(secondsFromUntil))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Relock by time does not change the stored state")
    void relockByTimeDoesNotChangeTheStoredState() {
        Loan loan = newLoan();
        Instant until = NOW.plus(Duration.ofDays(1));
        loan.apply(100, UnlockDecision.unlock(1, 0, until));

        assertSoftly(softly -> {
            softly.assertThat(loan.view(until).deviceState()).isEqualTo(DeviceState.LOCKED);
            softly.assertThat(loan.storedDeviceState()).isEqualTo(DeviceState.UNLOCKED);
        });
    }

    @Test
    @DisplayName("Overpayment shows a zero balance, the extra money is not visible (F-02)")
    @Tag("F-02")
    void overpaymentShowsZeroBalance() {
        Loan loan = newLoan();

        loan.apply(PRICE + 500, UnlockDecision.release());

        assertSoftly(softly -> {
            softly.assertThat(loan.view(NOW).paid()).isEqualTo(PRICE + 500);
            softly.assertThat(loan.view(NOW).balance()).isZero();
        });
    }

    private static Loan newLoan() {
        return new Loan("LN-1", "350000000000001", PRICE, DAILY_RATE);
    }
}
