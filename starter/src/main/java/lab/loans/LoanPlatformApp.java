package lab.loans;

import lab.loans.api.HttpApi;
import lab.loans.events.InMemoryEventBus;
import lab.loans.knox.DeviceLockClient;
import lab.loans.knox.HttpDeviceLockClient;
import lab.loans.knox.LoggingDeviceLockClient;
import lab.loans.service.PaymentProcessor;
import lab.loans.store.LoanRepository;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/** The system under test: wires the repository, the event bus, the consumer and the HTTP API. */
public final class LoanPlatformApp implements AutoCloseable {

    private final HttpApi api;
    private final InMemoryEventBus bus;
    private final int port;

    private LoanPlatformApp(HttpApi api, InMemoryEventBus bus, int port) {
        this.api = api;
        this.bus = bus;
        this.port = port;
    }

    public static LoanPlatformApp start(int port, DeviceLockClient deviceLock, Clock clock) throws IOException {
        return start("127.0.0.1", port, deviceLock, clock);
    }

    public static LoanPlatformApp start(String host, int port, DeviceLockClient deviceLock, Clock clock)
            throws IOException {
        LoanRepository loans = new LoanRepository();
        InMemoryEventBus bus = new InMemoryEventBus();
        PaymentProcessor processor = new PaymentProcessor(loans, deviceLock, clock);
        bus.subscribe(PaymentProcessor.TOPIC, processor::handle);

        HttpApi api = new HttpApi(loans, bus, clock);
        int actualPort = api.start(host, port);
        return new LoanPlatformApp(api, bus, actualPort);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public List<InMemoryEventBus.DeadLetter> deadLetters() {
        return bus.deadLetters();
    }

    @Override
    public void close() {
        api.stop();
        bus.close();
    }

    /**
     * Local run: Run in the IDE. In a container the BIND_HOST and PORT environment variables
     * (Render sets PORT) win over -Dhost and -Dport. -Dknox.url=... sends real HTTP to a stub.
     */
    public static void main(String[] args) throws IOException {
        String host = setting("BIND_HOST", "host", "127.0.0.1");
        int port = Integer.parseInt(setting("PORT", "port", "8080"));
        String knoxUrl = System.getProperty("knox.url");
        DeviceLockClient deviceLock = knoxUrl == null
                ? new LoggingDeviceLockClient()
                : new HttpDeviceLockClient(knoxUrl);
        LoanPlatformApp app = start(host, port, deviceLock, Clock.systemUTC());
        System.out.println("Loan platform is listening on http://" + host + ":" + app.port);
    }

    private static String setting(String envName, String propertyName, String fallback) {
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getProperty(propertyName, fallback);
    }
}
