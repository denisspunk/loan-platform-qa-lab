package lab.loans.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A Kafka stand-in that keeps the two guarantees tests care about:
 * delivery is asynchronous, and a handler that keeps failing sends the event to a dead-letter list.
 * One worker thread keeps every event in order (real Kafka only keeps order within a partition,
 * that is, per key).
 */
public class InMemoryEventBus implements EventBus, AutoCloseable {

    public static final int MAX_ATTEMPTS = 3;

    public record DeadLetter(String topic, String key, Object event, String error) {
    }

    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();
    private final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "event-bus");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void subscribe(String topic, Consumer<Object> handler) {
        subscribers.computeIfAbsent(topic, name -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void publish(String topic, String key, Object event) {
        worker.submit(() -> deliver(topic, key, event));
    }

    private void deliver(String topic, String key, Object event) {
        for (Consumer<Object> handler : subscribers.getOrDefault(topic, List.of())) {
            RuntimeException lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    handler.accept(event);
                    lastError = null;
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                }
            }
            if (lastError != null) {
                deadLetters.add(new DeadLetter(topic, key, event, lastError.getMessage()));
            }
        }
    }

    public List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
