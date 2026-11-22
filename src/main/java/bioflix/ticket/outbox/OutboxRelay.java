package bioflix.ticket.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Publishes unpublished outbox events to RabbitMQ and stamps publishedAt once the broker has confirmed them.
 * At-least-once: if the service dies after publishing but before stamping, the event is sent again
 * (consumers skip it by eventId).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository repository, RabbitTemplate rabbitTemplate,
                       @Value("${app.rabbitmq.exchange}") String exchange,
                       @Value("${app.outbox.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1s}")
    public void publishPending() {
        List<OutboxEvent> pending = repository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(batchSize));
        for (OutboxEvent event : pending) {
            try {
                publish(event);
            } catch (AmqpException ex) {
                log.warn("Could not publish {} {} – will retry: {}", event.getEventType(), event.getEventId(), ex.getMessage());
                return;   // keep the order; try again on the next poll
            }
            event.markPublished();
            repository.save(event);
        }
    }

    private void publish(OutboxEvent event) {
        Message message = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.getEventId())
                .setType(event.getEventType())
                .build();
        rabbitTemplate.invoke(ops -> {
            ops.send(exchange, event.getEventType(), message);
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
