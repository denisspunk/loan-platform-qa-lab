package lab.loans.knox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lab.loans.Bugs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Partner contract (simplified, not the real Samsung Knox API):
 *   POST /devices/{deviceId}/unlock   body {}
 *   POST /devices/{deviceId}/relock   body {"relockAt": "2026-09-16T09:00:00Z"}
 *   POST /devices/{deviceId}/release  body {}
 * Every call carries Content-Type: application/json and X-Correlation-Id.
 */
public class HttpDeviceLockClient implements DeviceLockClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;

    public HttpDeviceLockClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public void unlock(String deviceId, String correlationId) {
        post("/devices/" + deviceId + "/unlock", Map.of(), correlationId);
    }

    @Override
    public void scheduleRelock(String deviceId, Instant relockAt, String correlationId) {
        Object when = Bugs.KNOX_EPOCH_DATE.isOn() ? relockAt.toEpochMilli() : relockAt.toString();
        post("/devices/" + deviceId + "/relock", Map.of("relockAt", when), correlationId);
    }

    @Override
    public void release(String deviceId, String correlationId) {
        post("/devices/" + deviceId + "/release", Map.of(), correlationId);
    }

    private void post(String path, Map<String, Object> body, String correlationId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new DeviceLockException("POST " + path + " returned " + response.statusCode());
            }
        } catch (IOException e) {
            throw new DeviceLockException("POST " + path + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceLockException("POST " + path + " interrupted");
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
