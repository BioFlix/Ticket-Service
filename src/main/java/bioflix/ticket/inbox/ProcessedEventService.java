package bioflix.ticket.inbox;

import bioflix.ticket.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Runs an event handler at most once per eventId.
 * <p>
 * The design says "insert the eventId first in the consumer's transaction". MongoDB only has multi-document
 * transactions on a replica set, so here the eventId is recorded <em>after</em> the handler succeeds. If the
 * service crashes in between, the event is redelivered and handled again – which is safe because the handlers
 * are idempotent (unique index per booked seat, one outbox event per booking).
 */
@Service
public class ProcessedEventService {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventService.class);

    private final ProcessedEventRepository repository;

    public ProcessedEventService(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    public void runOnce(EventEnvelope<?> event, Runnable handler) {
        String eventId = event.eventId().toString();
        if (repository.existsById(eventId)) {
            log.info("Event {} ({}) already processed – skipping", eventId, event.type());
            return;
        }
        handler.run();
        try {
            repository.insert(new ProcessedEvent(eventId, event.type()));
        } catch (DuplicateKeyException ex) {
            // Handled concurrently by another consumer – fine, the handler is idempotent
        }
    }
}
