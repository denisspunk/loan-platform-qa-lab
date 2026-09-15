package lab.loans.events;

import java.util.function.Consumer;

/** The part of Kafka this service uses: publish to a topic, subscribe to a topic. */
public interface EventBus {

    void publish(String topic, String key, Object event);

    void subscribe(String topic, Consumer<Object> handler);
}
