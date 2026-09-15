package lab.loans.store;

import lab.loans.domain.Loan;

import java.util.Optional;

/**
 * Where loans and the payments applied to them live.
 * InMemoryLoanRepository serves tests and local runs; JdbcLoanRepository serves Postgres when DATABASE_URL is set.
 */
public interface LoanRepository {

    Loan save(Loan loan);

    Optional<Loan> findById(String id);

    boolean isPaymentProcessed(String paymentId);

    /** Stores the loan state after a payment and remembers the payment as one step: both or neither. */
    void recordPayment(Loan loan, ProcessedPayment payment);
}
