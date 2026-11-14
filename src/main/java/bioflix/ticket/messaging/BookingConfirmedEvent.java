package bioflix.ticket.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Published by booking-service on routing key {@code booking.confirmed} once the booking is paid.
 * Extra fields (price, currency, ...) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingConfirmedEvent(
        Long bookingId,
        Long userId,
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startTime,
        List<String> seats
) {
}
