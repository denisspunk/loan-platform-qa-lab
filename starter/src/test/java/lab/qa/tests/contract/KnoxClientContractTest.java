package lab.qa.tests.contract;

import lab.loans.knox.DeviceLockClient;
import lab.loans.knox.DeviceLockException;
import lab.loans.knox.HttpDeviceLockClient;
import lab.qa.clients.KnoxStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static lab.qa.data.TestData.uniqueImei;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the partner contract: does our client send exactly what the partner expects?
 * The same idea as Pact, done by hand: the expected request lives in the test.
 * Every test uses its own IMEI, so the recorded calls of one test never leak into another.
 */
@Tag("contract")
@DisplayName("Partner contract: what our device-lock client sends to the partner")
class KnoxClientContractTest {

    private static final KnoxStub knox = new KnoxStub();

    private DeviceLockClient client;
    private String imei;

    @BeforeAll
    static void startStub() {
        knox.start();
    }

    @AfterAll
    static void stopStub() {
        knox.stop();
    }

    @BeforeEach
    void newClientAndPhone() {
        client = new HttpDeviceLockClient(knox.baseUrl());
        imei = uniqueImei();
    }

    @Test
    @DisplayName("Unlock sends an empty JSON object with the correlation id, once")
    void unlockSendsAnEmptyJsonObject() {
        client.unlock(imei, "MPESA-1");

        knox.server().verify(1, postRequestedFor(urlEqualTo("/devices/" + imei + "/unlock"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Correlation-Id", equalTo("MPESA-1"))
                .withRequestBody(equalToJson("{}")));
    }

    @Test
    @DisplayName("Relock sends the relock moment as an ISO-8601 string with the correlation id, once")
    void relockSendsAnIsoInstant() {
        client.scheduleRelock(imei, Instant.parse("2026-09-16T09:00:00Z"), "MPESA-2");

        knox.server().verify(1, postRequestedFor(urlEqualTo("/devices/" + imei + "/relock"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Correlation-Id", equalTo("MPESA-2"))
                .withRequestBody(equalToJson("{\"relockAt\": \"2026-09-16T09:00:00Z\"}")));
    }

    @Test
    @DisplayName("Relock keeps fractions of a second in the relock moment (F-07)")
    void relockKeepsFractionsOfASecond() {
        client.scheduleRelock(imei, Instant.parse("2026-09-16T09:00:00.244206Z"), "MPESA-3");

        knox.server().verify(1, postRequestedFor(urlEqualTo("/devices/" + imei + "/relock"))
                .withRequestBody(equalToJson("{\"relockAt\": \"2026-09-16T09:00:00.244206Z\"}")));
    }

    @Test
    @DisplayName("Release sends an empty JSON object with the correlation id, once")
    void releaseSendsAnEmptyJsonObject() {
        client.release(imei, "MPESA-4");

        knox.server().verify(1, postRequestedFor(urlEqualTo("/devices/" + imei + "/release"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Correlation-Id", equalTo("MPESA-4"))
                .withRequestBody(equalToJson("{}")));
    }

    @DisplayName("Partner error on any action becomes a DeviceLockException")
    @ParameterizedTest(name = "{0} answered 503 -> DeviceLockException")
    @ValueSource(strings = {"unlock", "relock", "release"})
    void partnerErrorBecomesDeviceLockException(String action) {
        knox.respondWith(imei, action, 503);

        assertThatThrownBy(() -> call(action))
                .isInstanceOf(DeviceLockException.class)
                .hasMessageContaining("/devices/" + imei + "/" + action)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("Unreachable partner becomes a DeviceLockException")
    void unreachablePartnerBecomesDeviceLockException() {
        DeviceLockClient nowhere = new HttpDeviceLockClient("http://127.0.0.1:1");

        assertThatThrownBy(() -> nowhere.unlock(imei, "MPESA-5"))
                .isInstanceOf(DeviceLockException.class)
                .hasMessageContaining("failed");
    }

    private void call(String action) {
        switch (action) {
            case "unlock" -> client.unlock(imei, "MPESA-6");
            case "relock" -> client.scheduleRelock(imei, Instant.parse("2026-09-16T09:00:00Z"), "MPESA-6");
            case "release" -> client.release(imei, "MPESA-6");
            default -> throw new IllegalArgumentException("unknown partner action: " + action);
        }
    }
}
