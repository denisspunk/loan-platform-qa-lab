package lab.qa.tests.smoke;

import lab.qa.clients.LoanJson;
import lab.qa.core.RemoteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static lab.qa.data.TestData.aLoan;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/** The first check after a deploy: the stand is up, a loan opens and a payment goes all the way through. */
@Tag("smoke")
@DisplayName("Stand smoke: the deployed service is up and a payment goes through")
class StandSmokeTest extends RemoteBase {

    /** The stand and this machine keep time separately; allow a little drift between the two clocks. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    @Test
    @DisplayName("Health check answers UP")
    void healthCheckAnswersUp() {
        loansApi.health()
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @DisplayName("Web UI page is served")
    void webUiPageIsServed() {
        loansApi.homePage()
                .then().statusCode(200)
                .contentType(startsWith("text/html"))
                .body(containsString("<title>Loan Platform</title>"));
    }

    @Test
    @DisplayName("Loan can be opened and read back with a locked phone")
    void loanCanBeOpenedAndReadBack() {
        LoanJson opened = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        LoanJson read = loanSteps.current(opened);

        assertSoftly(softly -> {
            softly.assertThat(read).isEqualTo(opened);
            softly.assertThat(read.deviceState()).isEqualTo("LOCKED");
        });
    }

    @Test
    @DisplayName("Payment is applied and unlocks the phone for a day")
    void paymentUnlocksThePhoneForADay() {
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));

        Instant before = Instant.now();
        LoanJson paid = paymentSteps.pay(loan, 150);
        Instant after = Instant.now();

        assertSoftly(softly -> {
            softly.assertThat(paid.deviceState()).isEqualTo("UNLOCKED");
            softly.assertThat(paid.credit()).isEqualTo(50);
            softly.assertThat(paid.unlockedUntil()).isNotNull();
            if (paid.unlockedUntil() != null) {
                softly.assertThat(Instant.parse(paid.unlockedUntil()))
                        .as("a day from the moment of payment, by the stand's real clock")
                        .isBetween(before.plus(Duration.ofDays(1)).minus(CLOCK_SKEW),
                                after.plus(Duration.ofDays(1)).plus(CLOCK_SKEW));
            }
        });
    }
}
