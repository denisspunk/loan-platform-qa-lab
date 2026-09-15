package lab.qa.tests.e2e;

import io.restassured.path.json.JsonPath;
import lab.qa.clients.LoanJson;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static lab.qa.data.TestData.aLoan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Journeys a customer really goes through, over days of the test clock:
 * HTTP in, event bus, processor, partner out. The clock is shared by the class,
 * so every journey counts its dates from clock.instant() at its own start.
 */
@Tag("e2e")
@DisplayName("Phone loan journeys: a customer over several days")
class PhoneLoanJourneyTest extends BaseIT {

    @Test
    @DisplayName("Customer pays day by day until the phone is theirs")
    void customerPaysDayByDayUntilThePhoneIsTheirs() {
        Instant start = clock.instant();
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        assertSoftly(softly -> {
            softly.assertThat(loan.deviceState()).isEqualTo("LOCKED");
            softly.assertThat(loan.balance()).isEqualTo(300);
        });

        LoanJson afterFirstDay = paymentSteps.pay(loan, 100);
        String firstRelock = start.plus(Duration.ofDays(1)).toString();
        assertSoftly(softly -> {
            softly.assertThat(afterFirstDay.deviceState()).isEqualTo("UNLOCKED");
            softly.assertThat(afterFirstDay.unlockedUntil()).isEqualTo(firstRelock);
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).hasSize(1);
            softly.assertThat(knox.callsFor(loan.deviceId(), "relock"))
                    .extracting(call -> JsonPath.from(call.getBodyAsString()).getString("relockAt"))
                    .containsExactly(firstRelock);
        });

        clock.advance(Duration.ofHours(25));
        assertThat(loanSteps.current(loan).deviceState())
                .as("a day and an hour later the phone has relocked by itself")
                .isEqualTo("LOCKED");

        LoanJson paidOff = paymentSteps.pay(loan, 200);
        assertSoftly(softly -> {
            softly.assertThat(paidOff.status()).isEqualTo("PAID_OFF");
            softly.assertThat(paidOff.deviceState()).isEqualTo("RELEASED");
            softly.assertThat(paidOff.balance()).isZero();
            softly.assertThat(knox.callsFor(loan.deviceId(), "release")).hasSize(1);
        });
    }

    @Test
    @DisplayName("Small payments buy a day only when they add up")
    void smallPaymentsBuyADayOnlyWhenTheyAddUp() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson afterSixty = paymentSteps.pay(loan, 60);
        assertSoftly(softly -> {
            softly.assertThat(afterSixty.deviceState()).isEqualTo("LOCKED");
            softly.assertThat(afterSixty.credit()).isEqualTo(60);
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).as("unlock calls after 60 KES").isEmpty();
        });

        LoanJson afterHundredTwenty = paymentSteps.pay(loan, 60);
        assertSoftly(softly -> {
            softly.assertThat(afterHundredTwenty.deviceState()).isEqualTo("UNLOCKED");
            softly.assertThat(afterHundredTwenty.credit()).isEqualTo(20);
            softly.assertThat(knox.callsFor(loan.deviceId(), "unlock")).as("unlock calls after 120 KES").hasSize(1);
        });
    }

    @Test
    @DisplayName("Paying again while unlocked extends the phone, which relocks exactly at the new time")
    void payingAgainWhileUnlockedExtendsThePhone() {
        Instant start = clock.instant();
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        paymentSteps.pay(loan, 100);
        clock.advance(Duration.ofHours(12));
        LoanJson extended = paymentSteps.pay(loan, 100);

        // the second day goes on top of the first one, not a day from the moment of the second payment
        Instant newUntil = start.plus(Duration.ofDays(2));
        assertSoftly(softly -> {
            softly.assertThat(extended.deviceState()).isEqualTo("UNLOCKED");
            softly.assertThat(extended.unlockedUntil()).isEqualTo(newUntil.toString());
            softly.assertThat(knox.callsFor(loan.deviceId(), "relock"))
                    .extracting(call -> JsonPath.from(call.getBodyAsString()).getString("relockAt"))
                    .containsExactly(start.plus(Duration.ofDays(1)).toString(), newUntil.toString());
        });

        clock.advance(Duration.between(clock.instant(), newUntil).minusMinutes(1));
        assertThat(loanSteps.current(loan).deviceState())
                .as("one minute before the new relock time")
                .isEqualTo("UNLOCKED");

        clock.advance(Duration.ofMinutes(1));
        assertThat(loanSteps.current(loan).deviceState())
                .as("exactly at the new relock time")
                .isEqualTo("LOCKED");
    }
}
