package lab.loans.service;

public class UnknownLoanException extends RuntimeException {

    public UnknownLoanException(String loanId) {
        super("loan " + loanId + " not found");
    }
}
