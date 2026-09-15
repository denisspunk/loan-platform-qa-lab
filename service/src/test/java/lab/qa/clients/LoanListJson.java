package lab.qa.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** GET /loans as the test framework sees it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanListJson(List<LoanJson> loans) {
}
