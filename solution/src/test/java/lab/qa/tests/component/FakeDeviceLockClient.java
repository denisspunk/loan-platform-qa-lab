package lab.qa.tests.component;

import lab.loans.knox.DeviceLockClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake, not a mock: a tiny working implementation that records calls.
 * Thread-safe, because the event bus calls it from its own thread.
 */
public class FakeDeviceLockClient implements DeviceLockClient {

    public record Call(String action, String deviceId, Instant relockAt, String correlationId) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public void unlock(String deviceId, String correlationId) {
        calls.add(new Call("unlock", deviceId, null, correlationId));
    }

    @Override
    public void scheduleRelock(String deviceId, Instant relockAt, String correlationId) {
        calls.add(new Call("relock", deviceId, relockAt, correlationId));
    }

    @Override
    public void release(String deviceId, String correlationId) {
        calls.add(new Call("release", deviceId, null, correlationId));
    }

    public List<Call> callsFor(String deviceId, String action) {
        return calls.stream()
                .filter(call -> call.deviceId().equals(deviceId))
                .filter(call -> call.action().equals(action))
                .toList();
    }
}
