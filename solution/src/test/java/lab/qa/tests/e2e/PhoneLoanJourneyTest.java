package lab.qa.tests.e2e;

import lab.qa.clients.LoanJson;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static lab.qa.data.TestData.aLoan;
import static lab.qa.dsl.LoanAssert.assertThatLoan;

/** One or two journeys a customer really goes through, written only in DSL steps. */
@Tag("e2e")
class PhoneLoanJourneyTest extends BaseIT {

    @Test
    void customerPaysDayByDayUntilThePhoneIsTheirs() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(300).dailyRate(100));
        assertThatLoan(loan).hasDeviceState("LOCKED").hasBalance(300);

        LoanJson afterFirstDay = paymentSteps.pay(loan, 100);
        assertThatLoan(afterFirstDay).hasDeviceState("UNLOCKED").isUnlockedUntil("2026-09-16T09:00:00Z");
        deviceSteps.shouldHaveUnlocked(loan, 1).shouldHaveScheduledRelockAt(loan, "2026-09-16T09:00:00Z");

        clock.advance(Duration.ofHours(25));
        assertThatLoan(loanSteps.current(loan)).hasDeviceState("LOCKED");

        LoanJson paidOff = paymentSteps.pay(loan, 200);
        assertThatLoan(paidOff).hasStatus("PAID_OFF").hasDeviceState("RELEASED").hasBalance(0);
        deviceSteps.shouldHaveReleased(loan);
    }

    @Test
    void smallPaymentsBuyADayOnlyWhenTheyAddUp() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson afterSixty = paymentSteps.pay(loan, 60);
        assertThatLoan(afterSixty).hasDeviceState("LOCKED").hasCredit(60);
        deviceSteps.shouldNotHaveContactedThePartner(loan);

        LoanJson afterHundredTwenty = paymentSteps.pay(loan, 60);
        assertThatLoan(afterHundredTwenty).hasDeviceState("UNLOCKED").hasCredit(20);
        deviceSteps.shouldHaveUnlocked(loan, 1);
    }
}
