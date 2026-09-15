package lab.qa.tests.contract;

import lab.loans.knox.DeviceLockClient;
import lab.loans.knox.DeviceLockException;
import lab.loans.knox.HttpDeviceLockClient;
import lab.qa.clients.KnoxStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the partner contract: does our client send exactly what the partner expects?
 * The same idea as Pact, done by hand: the expected request lives in the test.
 */
@Tag("contract")
class KnoxClientContractTest {

    private static final String IMEI = "350000000000001";
    private static final KnoxStub knox = new KnoxStub();

    private DeviceLockClient client;

    @BeforeAll
    static void startStub() {
        knox.start();
    }

    @AfterAll
    static void stopStub() {
        knox.stop();
    }

    @BeforeEach
    void newClient() {
        knox.forgetCalls();
        client = new HttpDeviceLockClient(knox.baseUrl());
    }

    @Test
    void relockSendsAnIsoInstantAndTheCorrelationId() {
        client.scheduleRelock(IMEI, Instant.parse("2026-09-16T09:00:00Z"), "MPESA-1");

        knox.server().verify(postRequestedFor(urlEqualTo("/devices/" + IMEI + "/relock"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Correlation-Id", equalTo("MPESA-1"))
                .withRequestBody(equalToJson("{\"relockAt\": \"2026-09-16T09:00:00Z\"}")));
    }

    @Test
    void unlockSendsAnEmptyJsonObject() {
        client.unlock(IMEI, "MPESA-2");

        knox.server().verify(postRequestedFor(urlEqualTo("/devices/" + IMEI + "/unlock"))
                .withHeader("X-Correlation-Id", equalTo("MPESA-2"))
                .withRequestBody(equalToJson("{}")));
    }

    @Test
    void partnerErrorBecomesDeviceLockException() {
        knox.respondWith("350000000000999", "release", 503);

        assertThatThrownBy(() -> client.release("350000000000999", "MPESA-3"))
                .isInstanceOf(DeviceLockException.class)
                .hasMessageContaining("503");
    }
}
