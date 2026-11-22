package bioflix.ticket.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** data of booking.cancelled. reason: USER | PAYMENT_FAILED | EXPIRED | SHOWTIME_CANCELLED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingCancelled(
        String bookingId,
        String userId,
        String reason,
        Boolean wasPaid
) {
}
