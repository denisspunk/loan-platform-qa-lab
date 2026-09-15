package lab.qa.tests.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import lab.loans.LoanPlatformApp;
import lab.loans.knox.HttpDeviceLockClient;
import lab.qa.clients.LoanRequest;
import lab.qa.clients.LoansApi;
import lab.qa.clients.PaymentRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

@Tag("integration")
@DisplayName("Payments API: HTTP in, event bus, processor, partner out")
class PaymentsApiTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    private static WireMockServer knox;
    private static LoanPlatformApp app;
    private static LoansApi loansApi;

    @BeforeAll
    static void startPartnerStubAndService() throws IOException {
        knox = new WireMockServer(options().dynamicPort());
        knox.start();
        knox.stubFor(post(urlPathMatching("/devices/.*")).willReturn(ok()));

        app = LoanPlatformApp.start(0, new HttpDeviceLockClient(knox.baseUrl()), Clock.fixed(NOW, ZoneOffset.UTC));
        loansApi = new LoansApi(app.baseUrl());
    }

    @AfterAll
    static void stopServiceAndStub() {
        app.close();
        knox.stop();
    }

    @Test
    @DisplayName("Accepted payment unlocks the phone through the partner")
    void acceptedPaymentUnlocksThePhoneThroughThePartner() {
        String loanId = loansApi.createLoan(new LoanRequest("350000000000501", 12_000, 100))
                .then().statusCode(201)
                .extract().path("id");

        loansApi.postPayment(new PaymentRequest("MPESA-501", loanId, 300))
                .then().statusCode(202);

        // 300 KES at 100 KES/day buys 3 days, counted from the fixed clock of this test
        String expectedUntil = NOW.plus(Duration.ofDays(3)).toString();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> loansApi.getLoan(loanId)
                .then().statusCode(200)
                .body("deviceState", equalTo("UNLOCKED"))
                .body("unlockedUntil", equalTo(expectedUntil)));

        assertThat(knox.findAll(postRequestedFor(urlEqualTo("/devices/350000000000501/relock"))))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.getHeader("X-Correlation-Id")).isEqualTo("MPESA-501");
                    assertThat(call.getBodyAsString()).contains(expectedUntil);
                });
    }
}
