package lab.loans.store;

import lab.loans.domain.Loan;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stand-in for Postgres. */
public class LoanRepository {

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();

    public Loan save(Loan loan) {
        loans.put(loan.id(), loan);
        return loan;
    }

    public Optional<Loan> findById(String id) {
        return Optional.ofNullable(loans.get(id));
    }
}
