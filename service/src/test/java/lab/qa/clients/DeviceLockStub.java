package lab.qa.clients;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** The device-lock partner, played by WireMock: answers 200 to every call and remembers it. */
public class DeviceLockStub {

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public void start() {
        server.start();
        server.stubFor(post(urlPathMatching("/devices/.*")).willReturn(ok()));
    }

    public void stop() {
        server.stop();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** Forget recorded calls; the stubs stay. */
    public void forgetCalls() {
        server.resetRequests();
    }

    /** Make one partner endpoint fail, e.g. respondWith("350...", "unlock", 503). */
    public void respondWith(String deviceId, String action, int status) {
        server.stubFor(post(urlEqualTo("/devices/" + deviceId + "/" + action))
                .atPriority(1)
                .willReturn(aResponse().withStatus(status)));
    }

    /** Calls for one device and action ("unlock", "relock", "release"), oldest first. */
    public List<LoggedRequest> callsFor(String deviceId, String action) {
        return server.findAll(postRequestedFor(urlEqualTo("/devices/" + deviceId + "/" + action)));
    }

    /** Raw WireMock for contract tests that verify exact request shapes. */
    public WireMockServer server() {
        return server;
    }
}
