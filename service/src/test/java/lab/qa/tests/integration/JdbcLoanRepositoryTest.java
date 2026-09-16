package lab.qa.tests.integration;

import lab.loans.domain.DeviceState;
import lab.loans.domain.Loan;
import lab.loans.domain.LoanStatus;
import lab.loans.domain.UnlockDecision;
import lab.loans.store.JdbcLoanRepository;
import lab.loans.store.ProcessedPayment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/** The JDBC repository against a real Postgres in Docker: schema, round trip and the rules the database enforces. */
@Tag("integration")
@DisplayName("JdbcLoanRepository against a real Postgres")
@Testcontainers
class JdbcLoanRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcLoanRepository repository;

    @BeforeAll
    static void createSchema() {
        repository = new JdbcLoanRepository(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .migrate()
                .migrate(); // twice on purpose: the schema runs on every start and must be safe to repeat
    }

    @Test
    @DisplayName("New loan is read back exactly as saved")
    void newLoanIsReadBackAsSaved() {
        Loan loan = repository.save(new Loan(newId(), "350000000000001", 12_000, 100));

        Loan stored = repository.findById(loan.id()).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(stored.deviceId()).isEqualTo("350000000000001");
            softly.assertThat(stored.price()).isEqualTo(12_000);
            softly.assertThat(stored.dailyRate()).isEqualTo(100);
            softly.assertThat(stored.paid()).isZero();
            softly.assertThat(stored.status()).isEqualTo(LoanStatus.ACTIVE);
            softly.assertThat(stored.storedDeviceState()).isEqualTo(DeviceState.LOCKED);
            softly.assertThat(stored.unlockedUntil()).isNull();
        });
    }

    @Test
    @DisplayName("Recorded payment stores the loan state and is remembered")
    void recordedPaymentStoresTheLoanStateAndIsRemembered() {
        Loan loan = repository.save(new Loan(newId(), "350000000000002", 12_000, 100));
        Instant paidUntil = NOW.plus(Duration.ofDays(1));
        loan.apply(150, UnlockDecision.unlock(1, 50, paidUntil));

        repository.recordPayment(loan, new ProcessedPayment("MPESA-" + loan.id(), loan.id(), 150, "APPLIED", NOW));

        Loan stored = repository.findById(loan.id()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(stored.paid()).isEqualTo(150);
            softly.assertThat(stored.credit()).isEqualTo(50);
            softly.assertThat(stored.storedDeviceState()).isEqualTo(DeviceState.UNLOCKED);
            softly.assertThat(stored.unlockedUntil()).isEqualTo(paidUntil);
            softly.assertThat(repository.isPaymentProcessed("MPESA-" + loan.id())).isTrue();
        });
    }

    @Test
    @DisplayName("Recording the same payment twice is not an error")
    @Tag("F-05")
    void samePaymentRecordedTwiceIsNotAnError() {
        Loan loan = repository.save(new Loan(newId(), "350000000000003", 12_000, 100));
        ProcessedPayment payment = new ProcessedPayment("MPESA-" + loan.id(), loan.id(), 50, "APPLIED", NOW);

        repository.recordPayment(loan, payment);
        repository.recordPayment(loan, payment);

        assertThat(repository.isPaymentProcessed(payment.paymentId())).isTrue();
    }

    @Test
    @DisplayName("Recent loans come newest first")
    void recentLoansComeNewestFirst() {
        Loan older = repository.save(new Loan(newId(), "350000000000005", 12_000, 100));
        Loan newer = repository.save(new Loan(newId(), "350000000000006", 12_000, 100));

        List<Loan> recent = repository.findRecent(2);

        assertThat(recent).extracting(Loan::id).containsExactly(newer.id(), older.id());
    }

    @Test
    @DisplayName("Payments of a loan come in processing order, without other loans' payments")
    void paymentsComeInProcessingOrder() {
        Loan loan = repository.save(new Loan(newId(), "350000000000007", 12_000, 100));
        Loan other = repository.save(new Loan(newId(), "350000000000008", 12_000, 100));
        repository.recordPayment(loan, new ProcessedPayment("MPESA-B-" + loan.id(), loan.id(), 50, "APPLIED", NOW.plusSeconds(60)));
        repository.recordPayment(loan, new ProcessedPayment("MPESA-A-" + loan.id(), loan.id(), 150, "APPLIED", NOW));
        repository.recordPayment(other, new ProcessedPayment("MPESA-" + other.id(), other.id(), 100, "APPLIED", NOW));

        List<ProcessedPayment> payments = repository.findPayments(loan.id());

        assertThat(payments)
                .extracting(ProcessedPayment::paymentId, ProcessedPayment::amount, ProcessedPayment::processedAt)
                .containsExactly(
                        tuple("MPESA-A-" + loan.id(), 150L, NOW),
                        tuple("MPESA-B-" + loan.id(), 50L, NOW.plusSeconds(60)));
    }

    @Test
    @DisplayName("Unknown loan and unknown payment are not found")
    void unknownLoanAndPaymentAreNotFound() {
        assertSoftly(softly -> {
            softly.assertThat(repository.findById("LN-missing")).isEmpty();
            softly.assertThat(repository.isPaymentProcessed("MPESA-missing")).isFalse();
        });
    }

    @Test
    @DisplayName("Database rejects a paid-off loan whose phone is still locked")
    void databaseRejectsAPaidOffLoanWithALockedPhone() {
        Loan broken = Loan.restore(newId(), "350000000000004", 300, 100, 300, 0,
                LoanStatus.PAID_OFF, DeviceState.LOCKED, null);

        assertThatThrownBy(() -> repository.save(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid_off_means_released");
    }

    @Test
    @DisplayName("Render-style postgresql:// URL is understood")
    void renderStyleDatabaseUrlIsUnderstood() {
        String url = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();

        JdbcLoanRepository fromUrl = JdbcLoanRepository.fromDatabaseUrl(url);

        assertThat(fromUrl.findById("LN-missing")).isEmpty();
    }

    private static String newId() {
        return "LN-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
