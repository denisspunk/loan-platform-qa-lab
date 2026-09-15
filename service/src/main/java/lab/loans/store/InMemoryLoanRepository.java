package lab.loans.store;

import lab.loans.domain.Loan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory storage for tests and local runs: everything is lost when the process stops.
 * Order is kept explicitly: under a test clock many loans and payments share the same timestamp.
 */
public class InMemoryLoanRepository implements LoanRepository {

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();
    private final List<String> loanIdsInOpeningOrder = new CopyOnWriteArrayList<>();
    private final Map<String, ProcessedPayment> payments = new ConcurrentHashMap<>();
    private final List<ProcessedPayment> paymentsInProcessingOrder = new CopyOnWriteArrayList<>();

    @Override
    public Loan save(Loan loan) {
        store(loan);
        return loan;
    }

    @Override
    public Optional<Loan> findById(String id) {
        return Optional.ofNullable(loans.get(id));
    }

    @Override
    public List<Loan> findRecent(int limit) {
        List<Loan> recent = new ArrayList<>();
        for (int i = loanIdsInOpeningOrder.size() - 1; i >= 0 && recent.size() < limit; i--) {
            recent.add(loans.get(loanIdsInOpeningOrder.get(i)));
        }
        return recent;
    }

    @Override
    public boolean isPaymentProcessed(String paymentId) {
        return payments.containsKey(paymentId);
    }

    @Override
    public void recordPayment(Loan loan, ProcessedPayment payment) {
        store(loan);
        if (payments.putIfAbsent(payment.paymentId(), payment) == null) {
            paymentsInProcessingOrder.add(payment);
        }
    }

    @Override
    public List<ProcessedPayment> findPayments(String loanId) {
        return paymentsInProcessingOrder.stream()
                .filter(payment -> payment.loanId().equals(loanId))
                .toList();
    }

    private void store(Loan loan) {
        if (loans.put(loan.id(), loan) == null) {
            loanIdsInOpeningOrder.add(loan.id());
        }
    }
}
