package bioflix.ticket.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Remembers that a booking was cancelled. booking.confirmed and booking.cancelled arrive on
 * separate queues, so a late booking.confirmed must not issue tickets for a cancelled booking.
 */
@Document(collection = "cancelled_bookings")
public class CancelledBooking {

    @Id
    private Long bookingId;

    private Instant cancelledAt;

    public CancelledBooking() {
    }

    public CancelledBooking(Long bookingId) {
        this.bookingId = bookingId;
        this.cancelledAt = Instant.now();
    }

    public Long getBookingId() { return bookingId; }
    public Instant getCancelledAt() { return cancelledAt; }
}
