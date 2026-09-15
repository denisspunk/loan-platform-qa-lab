package lab.qa.tests.integration;

import lab.qa.clients.LoanJson;
import lab.qa.core.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static lab.qa.data.TestData.aLoan;
import static lab.qa.data.TestData.uniquePaymentId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("integration")
@DisplayName("Payments API: HTTP in, event bus, processor, partner out")
class PaymentsApiTest extends BaseIT {

    @Test
    @DisplayName("Accepted payment unlocks the phone through the partner")
    void acceptedPaymentUnlocksThePhoneThroughThePartner() {
        Instant now = clock.instant();
        LoanJson loan = loanSteps.openLoan(aLoan().price(12_000).dailyRate(100));
        String paymentId = uniquePaymentId();

        LoanJson paid = paymentSteps.pay(loan, paymentId, 300);

        // 300 KES at 100 KES/day buys 3 days, counted from the test clock
        String expectedUntil = now.plus(Duration.ofDays(3)).toString();
        assertSoftly(softly -> {
            softly.assertThat(paid.deviceState()).isEqualTo("UNLOCKED");
            softly.assertThat(paid.unlockedUntil()).isEqualTo(expectedUntil);
        });
        assertThat(knox.callsFor(loan.deviceId(), "relock"))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.getHeader("X-Correlation-Id")).isEqualTo(paymentId);
                    assertThat(call.getBodyAsString()).contains(expectedUntil);
                });
    }
}
