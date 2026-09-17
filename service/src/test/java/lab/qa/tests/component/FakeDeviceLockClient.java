package lab.qa.tests.component;

import lab.loans.partner.DeviceLockClient;
import lab.loans.partner.DeviceLockException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake, not a mock: a tiny working implementation that records calls.
 * Thread-safe, because the event bus calls it from its own thread while the test reads it.
 */
public class FakeDeviceLockClient implements DeviceLockClient {

    public record Call(String action, String deviceId, Instant relockAt, String correlationId) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Set<String> failingActions = ConcurrentHashMap.newKeySet();

    @Override
    public void unlock(String deviceId, String correlationId) {
        record(new Call("unlock", deviceId, null, correlationId));
    }

    @Override
    public void scheduleRelock(String deviceId, Instant relockAt, String correlationId) {
        record(new Call("relock", deviceId, relockAt, correlationId));
    }

    @Override
    public void release(String deviceId, String correlationId) {
        record(new Call("release", deviceId, null, correlationId));
    }

    /** Makes one action fail for one device, the way the HTTP client fails on a 503. The call is still recorded. */
    public void failOn(String deviceId, String action) {
        failingActions.add(deviceId + "/" + action);
    }

    public List<Call> callsFor(String deviceId, String action) {
        return calls.stream()
                .filter(call -> call.deviceId().equals(deviceId))
                .filter(call -> call.action().equals(action))
                .toList();
    }

    private void record(Call call) {
        calls.add(call);
        if (failingActions.contains(call.deviceId() + "/" + call.action())) {
            throw new DeviceLockException("POST /devices/" + call.deviceId() + "/" + call.action() + " returned 503");
        }
    }
}
