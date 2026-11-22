package bioflix.ticket.inbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Inbox: the eventIds this service has already handled. _id is the eventId, so it is unique. */
@Document(collection = "processed_events")
public class ProcessedEvent {

    @Id
    private String eventId;

    private String eventType;
    private Instant processedAt;

    public ProcessedEvent() {
    }

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }
}
