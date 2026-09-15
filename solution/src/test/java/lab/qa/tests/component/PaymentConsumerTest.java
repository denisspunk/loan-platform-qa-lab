package lab.qa.tests.component;

import lab.loans.domain.Loan;
import lab.loans.events.InMemoryEventBus;
import lab.loans.events.InMemoryEventBus.DeadLetter;
import lab.loans.events.PaymentReceived;
import lab.loans.service.PaymentProcessor;
import lab.loans.store.LoanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static lab.loans.service.PaymentProcessor.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One service with its real consumer and event bus; only the partner is replaced by a fake.
 * These are the Kafka failure modes from the "what breaks" sheet, minus the container.
 */
@Tag("component")
class PaymentConsumerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final LoanRepository loans = new LoanRepository();
    private final FakeDeviceLockClient deviceLock = new FakeDeviceLockClient();
    private InMemoryEventBus bus;

    @BeforeEach
    void startConsumer() {
        bus = new InMemoryEventBus();
        PaymentProcessor processor = new PaymentProcessor(loans, deviceLock, Clock.fixed(NOW, ZoneOffset.UTC));
        bus.subscribe(TOPIC, processor::handle);
    }

    @AfterEach
    void stopConsumer() {
        bus.close();
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        Loan loan = loans.save(new Loan("LN-1", "IMEI-1", 10_000, 100));
        PaymentReceived payment = new PaymentReceived("MPESA-42", loan.id(), 1_500, NOW);

        // at-least-once: the broker may deliver the same event twice
        bus.publish(TOPIC, loan.id(), payment);
        bus.publish(TOPIC, loan.id(), payment);
        // same key, so the marker is consumed after both copies
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MARKER", loan.id(), 1, NOW));

        await().atMost(TIMEOUT).until(() -> loan.paid() >= 1_501);
        assertThat(loan.paid()).isEqualTo(1_501);
        assertThat(deviceLock.callsFor("IMEI-1", "unlock")).hasSize(1);
    }

    @Test
    void partialPaymentsAddUpInOrder() {
        Loan loan = loans.save(new Loan("LN-2", "IMEI-2", 10_000, 100));

        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-1", loan.id(), 30, NOW));
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-2", loan.id(), 30, NOW));
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-3", loan.id(), 40, NOW));

        await().atMost(TIMEOUT).until(() -> loan.paid() == 100);
        assertThat(loan.credit()).isZero();
        assertThat(deviceLock.callsFor("IMEI-2", "unlock"))
                .extracting(FakeDeviceLockClient.Call::correlationId)
                .containsExactly("MPESA-3");
        assertThat(deviceLock.callsFor("IMEI-2", "relock"))
                .extracting(FakeDeviceLockClient.Call::relockAt)
                .containsExactly(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void poisonMessagesGoToDeadLettersAndTheNextEventsStillFlow() {
        Loan loan = loans.save(new Loan("LN-3", "IMEI-3", 10_000, 100));

        bus.publish(TOPIC, "LN-404", new PaymentReceived("MPESA-X", "LN-404", 100, NOW));
        bus.publish(TOPIC, "junk", "not a payment at all");
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-1", loan.id(), 100, NOW));

        await().atMost(TIMEOUT).until(() -> loan.paid() == 100);
        assertThat(bus.deadLetters())
                .extracting(DeadLetter::key)
                .containsExactly("LN-404", "junk");
    }
}
