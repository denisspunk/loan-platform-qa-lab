package lab.loans.partner;

import java.time.Instant;

/**
 * How the loan platform talks to the device-lock partner.
 * A simplified device-lock partner API, not the real a device-locking service API.
 * Three implementations: HttpDeviceLockClient (real HTTP), LoggingDeviceLockClient (local run),
 * and the fakes and mocks the tests write themselves.
 */
public interface DeviceLockClient {

    void unlock(String deviceId, String correlationId);

    void scheduleRelock(String deviceId, Instant relockAt, String correlationId);

    void release(String deviceId, String correlationId);
}
