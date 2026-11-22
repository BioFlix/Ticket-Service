package bioflix.ticket.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** data of booking.confirmed (producer: booking-service). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingConfirmed(
        String bookingId,
        String userId,
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startsAt,
        List<BookedSeat> seats
) {
    /** price is in øre. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookedSeat(String row, Integer number, String type, Integer price) {
    }
}
