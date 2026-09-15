package lab.qa.tests.unit;

import lab.loans.domain.Loan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Lesson 1 warm-up: the smallest real test. Run it, then break it on purpose
 * and read the failure.
 */
@Tag("unit")
@DisplayName("First test: a brand-new loan")
class FirstTest {

    @Test
    @DisplayName("New loan owes the whole price")
    void newLoanOwesTheWholePrice() {
        Loan loan = new Loan("LN-1", "350000000000001", 12_000, 100);

        assertSoftly(softly -> {
            softly.assertThat(loan.paid()).isEqualTo(0);
            softly.assertThat(loan.credit()).isEqualTo(0);
            softly.assertThat(loan.price() - loan.paid()).isEqualTo(12_000);
        });
    }
}
