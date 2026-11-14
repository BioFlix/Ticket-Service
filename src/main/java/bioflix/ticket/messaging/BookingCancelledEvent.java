package bioflix.ticket.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Published by booking-service on routing key {@code booking.cancelled}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingCancelledEvent(
        Long bookingId,
        String reason
) {
}
