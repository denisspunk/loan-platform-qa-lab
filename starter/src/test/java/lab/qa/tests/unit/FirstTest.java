package lab.qa.tests.unit;

import lab.loans.domain.Loan;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lesson 1 warm-up: the smallest real test. Run it, then break it on purpose
 * and read the failure.
 */
@Tag("unit")
class FirstTest {

    @Test
    void newLoanOwesTheWholePrice() {
        Loan loan = new Loan("LN-1", "350000000000001", 12_000, 100);

        assertThat(loan.paid()).isEqualTo(0);
        assertThat(loan.credit()).isEqualTo(0);
        assertThat(loan.price() - loan.paid()).isEqualTo(12_000);
    }
}
