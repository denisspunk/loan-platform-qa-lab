package lab.qa.tests.unit;

import lab.loans.domain.Loan;
import lab.loans.domain.LoanStatus;
import lab.loans.events.PaymentReceived;
import lab.loans.knox.DeviceLockClient;
import lab.loans.service.PaymentProcessor;
import lab.loans.service.ProcessingResult;
import lab.loans.service.UnknownLoanException;
import lab.loans.store.LoanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PaymentProcessorTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");
    private static final String IMEI = "350000000000001";

    @Mock
    private DeviceLockClient deviceLock;

    private final LoanRepository loans = new LoanRepository();
    private PaymentProcessor processor;
    private Loan loan;

    @BeforeEach
    void setUp() {
        processor = new PaymentProcessor(loans, deviceLock, Clock.fixed(NOW, ZoneOffset.UTC));
        loan = loans.save(new Loan("LN-1", IMEI, 1_000, 100));
    }

    @Test
    void fullDayPaymentUnlocksAndSchedulesTheRelock() {
        processor.process(payment("MPESA-1", 200));

        verify(deviceLock).unlock(IMEI, "MPESA-1");
        verify(deviceLock).scheduleRelock(IMEI, NOW.plus(Duration.ofDays(2)), "MPESA-1");
        verifyNoMoreInteractions(deviceLock);
    }

    @Test
    void partialPaymentDoesNotCallThePartner() {
        processor.process(payment("MPESA-1", 60));

        verifyNoInteractions(deviceLock);
        assertThat(loan.credit()).isEqualTo(60);
    }

    @Test
    void paymentThatCoversThePriceReleasesTheLock() {
        processor.process(payment("MPESA-1", 1_000));

        verify(deviceLock).release(IMEI, "MPESA-1");
        verify(deviceLock, never()).scheduleRelock(anyString(), any(), anyString());
        assertThat(loan.status()).isEqualTo(LoanStatus.PAID_OFF);
    }

    @Test
    void samePaymentIdIsAppliedOnlyOnce() {
        ProcessingResult first = processor.process(payment("MPESA-1", 100));
        ProcessingResult second = processor.process(payment("MPESA-1", 100));

        assertThat(first).isEqualTo(ProcessingResult.APPLIED);
        assertThat(second).isEqualTo(ProcessingResult.DUPLICATE);
        assertThat(loan.paid()).isEqualTo(100);
        verify(deviceLock, times(1)).unlock(anyString(), anyString());
    }

    @Test
    void paymentForUnknownLoanFailsWithoutSideEffects() {
        PaymentReceived orphan = new PaymentReceived("MPESA-1", "LN-404", 100, NOW);

        assertThatThrownBy(() -> processor.process(orphan))
                .isInstanceOf(UnknownLoanException.class)
                .hasMessageContaining("LN-404");
        verifyNoInteractions(deviceLock);
    }

    private PaymentReceived payment(String paymentId, long amount) {
        return new PaymentReceived(paymentId, loan.id(), amount, NOW);
    }
}
