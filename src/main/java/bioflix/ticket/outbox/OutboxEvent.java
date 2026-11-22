package bioflix.ticket.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Outbox: events waiting to be published to RabbitMQ by {@link OutboxRelay}.
 * payload is the full envelope as JSON. One ticket.issued per booking (unique eventType + aggregateId).
 */
@Document(collection = "outbox_events")
@CompoundIndex(name = "one_event_per_aggregate", def = "{'eventType': 1, 'aggregateId': 1}", unique = true)
@CompoundIndex(name = "unpublished", def = "{'publishedAt': 1, 'createdAt': 1}")
public class OutboxEvent {

    @Id
    private String id;

    @Indexed(unique = true)
    private String eventId;

    private String eventType;     // also the routing key
    private String aggregateId;   // bookingId
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

    public OutboxEvent() {
    }

    public OutboxEvent(String eventId, String eventType, String aggregateId, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
