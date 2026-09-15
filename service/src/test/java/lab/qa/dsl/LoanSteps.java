package lab.qa.dsl;

import lab.qa.clients.LoanJson;
import lab.qa.clients.LoanListJson;
import lab.qa.clients.LoansApi;
import lab.qa.clients.PaymentHistoryJson;
import lab.qa.data.TestData;

import java.util.List;

/** Business steps around a loan. A test says "open a loan", not "POST /loans and parse 201". */
public class LoanSteps {

    private final LoansApi api;

    public LoanSteps(LoansApi api) {
        this.api = api;
    }

    public LoanJson openLoan(TestData.LoanBuilder loan) {
        return api.createLoan(loan.build())
                .then().statusCode(201)
                .extract().as(LoanJson.class);
    }

    /** The most recently opened loans, newest first, as the web UI lists them. */
    public List<LoanJson> recentLoans(int limit) {
        return api.listLoans(limit)
                .then().statusCode(200)
                .extract().as(LoanListJson.class)
                .loans();
    }

    /** Payments recorded for the loan so far. Payments still queued are not there yet: wait for them first. */
    public PaymentHistoryJson paymentHistory(LoanJson loan) {
        return api.getPayments(loan.id())
                .then().statusCode(200)
                .extract().as(PaymentHistoryJson.class);
    }

    /** The loan as the API shows it right now. */
    public LoanJson current(LoanJson loan) {
        return api.getLoan(loan.id())
                .then().statusCode(200)
                .extract().as(LoanJson.class);
    }
}
