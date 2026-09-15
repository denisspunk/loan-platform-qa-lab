package lab.loans.store;

import lab.loans.domain.Loan;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory storage for tests and local runs: everything is lost when the process stops. */
public class InMemoryLoanRepository implements LoanRepository {

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();
    private final Map<String, ProcessedPayment> payments = new ConcurrentHashMap<>();

    @Override
    public Loan save(Loan loan) {
        loans.put(loan.id(), loan);
        return loan;
    }

    @Override
    public Optional<Loan> findById(String id) {
        return Optional.ofNullable(loans.get(id));
    }

    @Override
    public boolean isPaymentProcessed(String paymentId) {
        return payments.containsKey(paymentId);
    }

    @Override
    public void recordPayment(Loan loan, ProcessedPayment payment) {
        loans.put(loan.id(), loan);
        payments.putIfAbsent(payment.paymentId(), payment);
    }
}
