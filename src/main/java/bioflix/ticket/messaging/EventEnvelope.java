package bioflix.ticket.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every message on the cinema.events exchange uses. The routing key equals {@code type}.
 * correlationId is the bookingId and flows through the whole purchase.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        String producer,
        String correlationId,
        T data
) {
    public static <T> EventEnvelope<T> create(String type, String producer, String correlationId, T data) {
        return new EventEnvelope<>(UUID.randomUUID(), type, 1, Instant.now(), producer, correlationId, data);
    }
}
