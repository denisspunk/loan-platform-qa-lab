package lab.qa.core;

import lab.qa.clients.LoansApi;
import lab.qa.dsl.LoanSteps;
import lab.qa.dsl.PaymentSteps;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base class for tests against a deployed stand. Nothing is started here: the service already runs at
 * -Dlab.baseUrl. The clock and the partner are real, so these tests check behaviour, never exact dates or
 * partner calls. A free-tier stand may be asleep, so the first step waits until /health answers.
 */
public abstract class StandBase {

    protected static LoansApi loansApi;
    protected static LoanSteps loanSteps;
    protected static PaymentSteps paymentSteps;

    @BeforeAll
    static void connectToStand() {
        assertThat(Config.BASE_URL)
                .as("address of the stand, pass it as -Dlab.baseUrl=https://...")
                .isNotBlank();
        loansApi = new LoansApi(Config.BASE_URL);
        loanSteps = new LoanSteps(loansApi);
        paymentSteps = new PaymentSteps(loansApi, loanSteps);

        await("stand " + Config.BASE_URL + " answers GET /health")
                .atMost(Config.WAKE_UP_TIMEOUT)
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> loansApi.health().statusCode() == 200);
    }
}
