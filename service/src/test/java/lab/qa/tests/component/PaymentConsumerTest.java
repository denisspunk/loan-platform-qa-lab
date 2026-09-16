package lab.qa.tests.component;

import lab.loans.domain.DeviceState;
import lab.loans.domain.Loan;
import lab.loans.events.InMemoryEventBus;
import lab.loans.events.InMemoryEventBus.DeadLetter;
import lab.loans.events.PaymentReceived;
import lab.loans.service.PaymentProcessor;
import lab.loans.store.InMemoryLoanRepository;
import lab.loans.store.LoanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static lab.loans.service.PaymentProcessor.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

/**
 * One service with its real consumer, event bus and storage; only the partner is replaced by a fake.
 * These are the failure modes of Kafka consumers, minus the broker.
 */
@Tag("component")
@DisplayName("Payment consumer: real event bus and processor, fake partner")
class PaymentConsumerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final LoanRepository loans = new InMemoryLoanRepository();
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
    @DisplayName("Duplicate delivery of the same event is applied once")
    void duplicateDeliveryIsAppliedOnce() {
        Loan loan = loans.save(new Loan("LN-1", "IMEI-1", 10_000, 100));
        PaymentReceived payment = new PaymentReceived("MPESA-42", loan.id(), 1_500, NOW);

        // at-least-once: the broker may deliver the same event twice
        bus.publish(TOPIC, loan.id(), payment);
        bus.publish(TOPIC, loan.id(), payment);
        // same key, so the marker is consumed after both copies
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MARKER", loan.id(), 1, NOW));

        await().atMost(TIMEOUT).until(() -> loan.paid() >= 1_501);
        assertSoftly(softly -> {
            softly.assertThat(loan.paid()).isEqualTo(1_501);
            softly.assertThat(deviceLock.callsFor("IMEI-1", "unlock")).hasSize(1);
        });
    }

    @Test
    @DisplayName("Partial payments of one loan add up in the order they arrive")
    void partialPaymentsAddUpInOrder() {
        Loan loan = loans.save(new Loan("LN-2", "IMEI-2", 10_000, 100));

        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-1", loan.id(), 30, NOW));
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-2", loan.id(), 30, NOW));
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-3", loan.id(), 40, NOW));

        await().atMost(TIMEOUT).until(() -> loan.paid() == 100);
        assertSoftly(softly -> {
            softly.assertThat(loan.credit()).isZero();
            softly.assertThat(deviceLock.callsFor("IMEI-2", "unlock"))
                    .extracting(FakeDeviceLockClient.Call::correlationId)
                    .containsExactly("MPESA-3");
            softly.assertThat(deviceLock.callsFor("IMEI-2", "relock"))
                    .extracting(FakeDeviceLockClient.Call::relockAt)
                    .containsExactly(NOW.plus(Duration.ofDays(1)));
        });
    }

    @Test
    @DisplayName("Poison events go to dead letters and the next events still flow")
    void poisonEventsGoToDeadLettersAndTheNextEventsStillFlow() {
        Loan loan = loans.save(new Loan("LN-3", "IMEI-3", 10_000, 100));

        bus.publish(TOPIC, "LN-404", new PaymentReceived("MPESA-X", "LN-404", 100, NOW));
        bus.publish(TOPIC, "junk", "not a payment at all");
        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-1", loan.id(), 100, NOW));

        await().atMost(TIMEOUT).until(() -> loan.paid() == 100);
        assertSoftly(softly -> softly.assertThat(bus.deadLetters())
                .extracting(DeadLetter::key)
                .containsExactly("LN-404", "junk"));
    }

    @Test
    @DisplayName("Partner failure on relock sends the event to dead letters and leaves the loan unchanged (F-04)")
    @Tag("F-04")
    void partnerFailureOnRelockLeavesTheLoanUnchanged() {
        Loan loan = loans.save(new Loan("LN-4", "IMEI-4", 10_000, 100));
        deviceLock.failOn("IMEI-4", "relock");

        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-4", loan.id(), 300, NOW));
        waitForQueuedEvents();

        assertSoftly(softly -> {
            softly.assertThat(loan.paid()).isZero();
            softly.assertThat(loan.storedDeviceState()).isEqualTo(DeviceState.LOCKED);
            softly.assertThat(loans.isPaymentProcessed("MPESA-4")).as("payment marked as processed").isFalse();
            softly.assertThat(bus.deadLetters())
                    .extracting(DeadLetter::key, DeadLetter::error)
                    .containsExactly(tuple(loan.id(), "POST /devices/IMEI-4/relock returned 503"));
            softly.assertThat(deviceLock.callsFor("IMEI-4", "relock"))
                    .as("relock attempts, one per delivery attempt of the bus")
                    .hasSize(InMemoryEventBus.MAX_ATTEMPTS);
        });
    }

    @Test
    @Disabled("F-04: the processor unlocks the phone before the relock is scheduled; if relock fails, the phone stays unlocked")
    @DisplayName("Phone is not unlocked when the relock cannot be scheduled")
    @Tag("F-04")
    void phoneIsNotUnlockedWhenTheRelockFails() {
        Loan loan = loans.save(new Loan("LN-5", "IMEI-5", 10_000, 100));
        deviceLock.failOn("IMEI-5", "relock");

        bus.publish(TOPIC, loan.id(), new PaymentReceived("MPESA-5", loan.id(), 300, NOW));
        waitForQueuedEvents();

        assertThat(deviceLock.callsFor("IMEI-5", "unlock")).isEmpty();
    }

    /**
     * The bus handles events one at a time and in order, so once a 1 KES payment to a separate marker loan
     * is applied, every event published before it has been handled, including retries and dead letters.
     */
    private void waitForQueuedEvents() {
        Loan marker = loans.save(new Loan("LN-MARKER", "IMEI-MARKER", 10_000, 100));
        bus.publish(TOPIC, marker.id(), new PaymentReceived("MARKER", marker.id(), 1, NOW));
        await().atMost(TIMEOUT).until(() -> marker.paid() == 1);
    }
}
