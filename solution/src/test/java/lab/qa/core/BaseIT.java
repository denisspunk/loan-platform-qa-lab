package lab.qa.core;

import lab.loans.LoanPlatformApp;
import lab.loans.knox.HttpDeviceLockClient;
import lab.qa.clients.KnoxStub;
import lab.qa.clients.LoansApi;
import lab.qa.dsl.DeviceSteps;
import lab.qa.dsl.LoanSteps;
import lab.qa.dsl.PaymentSteps;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;

/**
 * Base class for tests that need the whole service running: starts the partner stub,
 * then the platform pointed at it, and builds the clients and DSL on top.
 * This is the only test class that knows how the system is started.
 */
public abstract class BaseIT {

    protected static TestClock clock;
    protected static KnoxStub knox;
    protected static LoanPlatformApp app;
    protected static LoansApi loansApi;

    protected static LoanSteps loanSteps;
    protected static PaymentSteps paymentSteps;
    protected static DeviceSteps deviceSteps;

    @BeforeAll
    static void startPlatform() throws IOException {
        clock = TestClock.startingAt(Config.START_TIME);
        knox = new KnoxStub();
        knox.start();
        app = LoanPlatformApp.start(0, new HttpDeviceLockClient(knox.baseUrl()), clock);
        loansApi = new LoansApi(app.baseUrl());

        loanSteps = new LoanSteps(loansApi);
        paymentSteps = new PaymentSteps(loansApi, loanSteps);
        deviceSteps = new DeviceSteps(knox);
    }

    @AfterAll
    static void stopPlatform() {
        app.close();
        knox.stop();
    }
}
