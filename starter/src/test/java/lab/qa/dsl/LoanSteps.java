package lab.qa.dsl;

import lab.qa.clients.LoanJson;
import lab.qa.clients.LoansApi;
import lab.qa.data.TestData;

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

    /** The loan as the API shows it right now. */
    public LoanJson current(LoanJson loan) {
        return api.getLoan(loan.id())
                .then().statusCode(200)
                .extract().as(LoanJson.class);
    }
}
