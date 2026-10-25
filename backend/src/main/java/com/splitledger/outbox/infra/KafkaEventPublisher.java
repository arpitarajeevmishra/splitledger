package com.splitledger.outbox.infra;

import com.splitledger.outbox.application.EventPublisher;
import com.splitledger.outbox.domain.EventEnvelope;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The bus this design is meant to scale out onto (ADR-005), not the one it is deployed with.
 *
 * <p>Kept and tested rather than deleted: ADR-015 drops the broker from the deployed runtime
 * because one small host running Redpanda for tens of users buys nothing, but the claim that
 * this is a broker-ready design is only worth making if something exercises it. CI does,
 * against an embedded broker. Switching to it is a property and a deploy.
 *
 * <p>Keyed by group, which is where per-group ordering comes from: Kafka orders within a
 * partition, and a key maps to a partition, so two expenses in one group cannot overtake
 * each other while unrelated groups stay free to interleave.
 */
@Component
// The exact complement of LocalEventBus's condition, so that every possible value of the
// property builds exactly one publisher and never none.
@ConditionalOnExpression("'${app.outbox.kafka.enabled:false}'.trim().equalsIgnoreCase('true')")
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final long sendTimeoutMs;

    KafkaEventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${app.outbox.kafka.topic}") String topic,
            @Value("${app.outbox.kafka.send-timeout-ms}") long sendTimeoutMs) {
        this.kafka = kafka;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * Waits for the broker's acknowledgement, deliberately.
     *
     * <p>{@code send()} returns a future, and returning without looking at it would let the
     * publisher stamp {@code published_at} on an event the broker never accepted -- which
     * is silent loss, the one outcome the outbox exists to prevent. The bounded wait is
     * what turns a broker that has stopped answering into a retry rather than a hung poll.
     */
    @Override
    public void publish(EventEnvelope event) {
        try {
            kafka.send(topic, event.key(), event.payload()).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Restore the flag before leaving: swallowing it would strip the only signal
            // that something asked this thread to stop.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing " + event.eventId(), interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("could not publish " + event.eventId(), failure);
        }
    }
}
