package lab.loans.store;

import lab.loans.domain.DeviceState;
import lab.loans.domain.Loan;
import lab.loans.domain.LoanStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Postgres over plain JDBC: a connection per call, no pool and no ORM, because the service applies
 * one payment at a time. The tables are described in src/main/resources/db/schema.sql.
 */
public class JdbcLoanRepository implements LoanRepository {

    private static final String UPSERT_LOAN = """
            INSERT INTO loans (id, device_id, price, daily_rate, paid, credit, status, device_state, unlocked_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                paid = EXCLUDED.paid,
                credit = EXCLUDED.credit,
                status = EXCLUDED.status,
                device_state = EXCLUDED.device_state,
                unlocked_until = EXCLUDED.unlocked_until
            """;

    private static final String SELECT_LOAN = """
            SELECT id, device_id, price, daily_rate, paid, credit, status, device_state, unlocked_until
            FROM loans
            WHERE id = ?
            """;

    private static final String SELECT_RECENT_LOANS = """
            SELECT id, device_id, price, daily_rate, paid, credit, status, device_state, unlocked_until
            FROM loans
            ORDER BY created_at DESC, id
            LIMIT ?
            """;

    private static final String SELECT_PAYMENTS = """
            SELECT payment_id, loan_id, amount, result, processed_at
            FROM processed_payments
            WHERE loan_id = ?
            ORDER BY processed_at, payment_id
            """;

    private static final String INSERT_PAYMENT = """
            INSERT INTO processed_payments (payment_id, loan_id, amount, result, processed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (payment_id) DO NOTHING
            """;

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public JdbcLoanRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /** Accepts postgresql://user:password@host:port/db, the form Render gives, or a ready jdbc:postgresql:// URL. */
    public static JdbcLoanRepository fromDatabaseUrl(String databaseUrl) {
        if (databaseUrl.startsWith("jdbc:")) {
            return new JdbcLoanRepository(databaseUrl, null, null);
        }
        try {
            URI uri = new URI(databaseUrl);
            String user = null;
            String password = null;
            if (uri.getRawUserInfo() != null) {
                String[] parts = uri.getRawUserInfo().split(":", 2);
                user = decode(parts[0]);
                password = parts.length > 1 ? decode(parts[1]) : null;
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query;
            return new JdbcLoanRepository(jdbcUrl, user, password);
        } catch (URISyntaxException e) {
            // the URL is not repeated here on purpose: it contains the password
            throw new IllegalArgumentException("DATABASE_URL is not a valid postgresql:// URL");
        }
    }

    /** Creates the tables if they are missing. Safe to run on every start. */
    public JdbcLoanRepository migrate() {
        String schema = readSchema();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException e) {
            throw failure("create the schema", e);
        }
        return this;
    }

    @Override
    public Loan save(Loan loan) {
        try (Connection connection = connect()) {
            upsert(connection, loan);
            return loan;
        } catch (SQLException e) {
            throw failure("save loan " + loan.id(), e);
        }
    }

    @Override
    public Optional<Loan> findById(String id) {
        try (Connection connection = connect(); PreparedStatement select = connection.prepareStatement(SELECT_LOAN)) {
            select.setString(1, id);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(toLoan(row)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find loan " + id, e);
        }
    }

    @Override
    public List<Loan> findRecent(int limit) {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(SELECT_RECENT_LOANS)) {
            select.setInt(1, limit);
            try (ResultSet row = select.executeQuery()) {
                List<Loan> recent = new ArrayList<>();
                while (row.next()) {
                    recent.add(toLoan(row));
                }
                return recent;
            }
        } catch (SQLException e) {
            throw failure("list recent loans", e);
        }
    }

    @Override
    public boolean isPaymentProcessed(String paymentId) {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT 1 FROM processed_payments WHERE payment_id = ?")) {
            select.setString(1, paymentId);
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        } catch (SQLException e) {
            throw failure("check payment " + paymentId, e);
        }
    }

    @Override
    public void recordPayment(Loan loan, ProcessedPayment payment) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                upsert(connection, loan);
                try (PreparedStatement insert = connection.prepareStatement(INSERT_PAYMENT)) {
                    insert.setString(1, payment.paymentId());
                    insert.setString(2, payment.loanId());
                    insert.setLong(3, payment.amount());
                    insert.setString(4, payment.result());
                    insert.setObject(5, utc(payment.processedAt()));
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw failure("record payment " + payment.paymentId(), e);
        }
    }

    @Override
    public List<ProcessedPayment> findPayments(String loanId) {
        try (Connection connection = connect(); PreparedStatement select = connection.prepareStatement(SELECT_PAYMENTS)) {
            select.setString(1, loanId);
            try (ResultSet row = select.executeQuery()) {
                List<ProcessedPayment> payments = new ArrayList<>();
                while (row.next()) {
                    payments.add(new ProcessedPayment(
                            row.getString("payment_id"),
                            row.getString("loan_id"),
                            row.getLong("amount"),
                            row.getString("result"),
                            row.getObject("processed_at", OffsetDateTime.class).toInstant()));
                }
                return payments;
            }
        } catch (SQLException e) {
            throw failure("list payments of loan " + loanId, e);
        }
    }

    private static Loan toLoan(ResultSet row) throws SQLException {
        OffsetDateTime unlockedUntil = row.getObject("unlocked_until", OffsetDateTime.class);
        return Loan.restore(
                row.getString("id"),
                row.getString("device_id"),
                row.getLong("price"),
                row.getLong("daily_rate"),
                row.getLong("paid"),
                row.getLong("credit"),
                LoanStatus.valueOf(row.getString("status")),
                DeviceState.valueOf(row.getString("device_state")),
                unlockedUntil == null ? null : unlockedUntil.toInstant());
    }

    private void upsert(Connection connection, Loan loan) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT_LOAN)) {
            upsert.setString(1, loan.id());
            upsert.setString(2, loan.deviceId());
            upsert.setLong(3, loan.price());
            upsert.setLong(4, loan.dailyRate());
            upsert.setLong(5, loan.paid());
            upsert.setLong(6, loan.credit());
            upsert.setString(7, loan.status().name());
            upsert.setString(8, loan.storedDeviceState().name());
            upsert.setObject(9, loan.unlockedUntil() == null ? null : utc(loan.unlockedUntil()));
            upsert.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String readSchema() {
        try (InputStream schema = JdbcLoanRepository.class.getResourceAsStream("/db/schema.sql")) {
            if (schema == null) {
                throw new IllegalStateException("db/schema.sql is missing from the classpath");
            }
            return new String(schema.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read db/schema.sql", e);
        }
    }

    private static IllegalStateException failure(String action, SQLException e) {
        return new IllegalStateException("database error while trying to " + action + ": " + e.getMessage(), e);
    }
}
