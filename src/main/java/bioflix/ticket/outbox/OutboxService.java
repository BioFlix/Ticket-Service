package bioflix.ticket.outbox;

import bioflix.ticket.messaging.EventEnvelope;
import bioflix.ticket.messaging.EventTypes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Writes events to the outbox. They are published later by {@link OutboxRelay}. */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxService(OutboxEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Adds the event unless one of the same type already exists for this aggregate.
     * @return true if it was added
     */
    public boolean enqueueOnce(String eventType, String aggregateId, String correlationId, Object data) {
        if (repository.existsByEventTypeAndAggregateId(eventType, aggregateId)) {
            return false;
        }
        EventEnvelope<Object> envelope = EventEnvelope.create(eventType, EventTypes.PRODUCER, correlationId, data);
        try {
            repository.insert(new OutboxEvent(envelope.eventId().toString(), eventType, aggregateId,
                    jsonMapper.writeValueAsString(envelope)));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
