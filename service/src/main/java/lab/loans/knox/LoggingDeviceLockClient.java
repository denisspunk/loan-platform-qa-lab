package lab.loans.knox;

import java.time.Instant;

/** Used when the app runs locally without a partner stub: prints what it would have sent. */
public class LoggingDeviceLockClient implements DeviceLockClient {

    @Override
    public void unlock(String deviceId, String correlationId) {
        System.out.printf("[knox] unlock %s (correlation %s)%n", deviceId, correlationId);
    }

    @Override
    public void scheduleRelock(String deviceId, Instant relockAt, String correlationId) {
        System.out.printf("[knox] relock %s at %s (correlation %s)%n", deviceId, relockAt, correlationId);
    }

    @Override
    public void release(String deviceId, String correlationId) {
        System.out.printf("[knox] release %s (correlation %s)%n", deviceId, correlationId);
    }
}
