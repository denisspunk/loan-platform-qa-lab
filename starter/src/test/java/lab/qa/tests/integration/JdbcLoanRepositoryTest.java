package lab.qa.tests.integration;

import lab.loans.domain.DeviceState;
import lab.loans.domain.Loan;
import lab.loans.domain.LoanStatus;
import lab.loans.domain.UnlockDecision;
import lab.loans.store.JdbcLoanRepository;
import lab.loans.store.ProcessedPayment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The JDBC repository against a real Postgres in Docker: schema, round trip and the rules the database enforces. */
@Tag("integration")
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
    void newLoanIsReadBackAsSaved() {
        Loan loan = repository.save(new Loan(newId(), "350000000000001", 12_000, 100));

        Loan stored = repository.findById(loan.id()).orElseThrow();

        assertThat(stored.deviceId()).isEqualTo("350000000000001");
        assertThat(stored.price()).isEqualTo(12_000);
        assertThat(stored.dailyRate()).isEqualTo(100);
        assertThat(stored.paid()).isZero();
        assertThat(stored.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(stored.storedDeviceState()).isEqualTo(DeviceState.LOCKED);
        assertThat(stored.unlockedUntil()).isNull();
    }

    @Test
    void recordedPaymentStoresTheLoanStateAndIsRemembered() {
        Loan loan = repository.save(new Loan(newId(), "350000000000002", 12_000, 100));
        Instant paidUntil = NOW.plus(Duration.ofDays(1));
        loan.apply(150, UnlockDecision.unlock(1, 50, paidUntil));

        repository.recordPayment(loan, new ProcessedPayment("MPESA-" + loan.id(), loan.id(), 150, "APPLIED", NOW));

        Loan stored = repository.findById(loan.id()).orElseThrow();
        assertThat(stored.paid()).isEqualTo(150);
        assertThat(stored.credit()).isEqualTo(50);
        assertThat(stored.storedDeviceState()).isEqualTo(DeviceState.UNLOCKED);
        assertThat(stored.unlockedUntil()).isEqualTo(paidUntil);
        assertThat(repository.isPaymentProcessed("MPESA-" + loan.id())).isTrue();
    }

    @Test
    void samePaymentRecordedTwiceIsNotAnError() {
        Loan loan = repository.save(new Loan(newId(), "350000000000003", 12_000, 100));
        ProcessedPayment payment = new ProcessedPayment("MPESA-" + loan.id(), loan.id(), 50, "APPLIED", NOW);

        repository.recordPayment(loan, payment);
        repository.recordPayment(loan, payment);

        assertThat(repository.isPaymentProcessed(payment.paymentId())).isTrue();
    }

    @Test
    void unknownLoanAndPaymentAreNotFound() {
        assertThat(repository.findById("LN-missing")).isEmpty();
        assertThat(repository.isPaymentProcessed("MPESA-missing")).isFalse();
    }

    @Test
    void databaseRejectsAPaidOffLoanWithALockedPhone() {
        Loan broken = Loan.restore(newId(), "350000000000004", 300, 100, 300, 0,
                LoanStatus.PAID_OFF, DeviceState.LOCKED, null);

        assertThatThrownBy(() -> repository.save(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid_off_means_released");
    }

    @Test
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
