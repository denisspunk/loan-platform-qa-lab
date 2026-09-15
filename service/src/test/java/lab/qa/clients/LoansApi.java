package lab.qa.clients;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** The only class that knows the platform's URLs. Tests and steps call methods, never paths. */
public class LoansApi {

    private final RequestSpecification spec;

    public LoansApi(String baseUrl) {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        this.spec = new RequestSpecBuilder()
                .setBaseUri(baseUrl)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    public Response createLoan(LoanRequest loan) {
        return given(spec).body(loan).when().post("/loans");
    }

    public Response getLoan(String loanId) {
        return given(spec).when().get("/loans/{id}", loanId);
    }

    public Response postPayment(PaymentRequest payment) {
        return given(spec).body(payment).when().post("/payments");
    }

    public Response health() {
        return given(spec).when().get("/health");
    }

    /** Sends the body as is, for tests of incomplete or malformed JSON that a record cannot express. */
    public Response postLoanBody(String rawBody) {
        return given(spec).body(rawBody).when().post("/loans");
    }

    /** Sends the body as is, for tests of incomplete or malformed JSON that a record cannot express. */
    public Response postPaymentBody(String rawBody) {
        return given(spec).body(rawBody).when().post("/payments");
    }

    /** Any method on any path, for routing tests: requests the service must refuse. */
    public Response request(String method, String path) {
        return given(spec).when().request(method, path);
    }
}
