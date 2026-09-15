package lab.loans.store;

import lab.loans.domain.Loan;

import java.util.List;
import java.util.Optional;

/**
 * Where loans and the payments applied to them live.
 * InMemoryLoanRepository serves tests and local runs; JdbcLoanRepository serves Postgres when DATABASE_URL is set.
 */
public interface LoanRepository {

    Loan save(Loan loan);

    Optional<Loan> findById(String id);

    /** The most recently opened loans, newest first. */
    List<Loan> findRecent(int limit);

    boolean isPaymentProcessed(String paymentId);

    /** Stores the loan state after a payment and remembers the payment as one step: both or neither. */
    void recordPayment(Loan loan, ProcessedPayment payment);

    /** Payments recorded for the loan, in the order they were processed. Duplicates are never recorded. */
    List<ProcessedPayment> findPayments(String loanId);
}
