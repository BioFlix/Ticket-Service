package bioflix.ticket.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Remembers that a booking was cancelled. booking.confirmed and booking.cancelled arrive on
 * separate queues, so a late booking.confirmed must not issue tickets for a cancelled booking.
 * (Not in the data model – an addition to handle out-of-order events.)
 */
@Document(collection = "cancelled_bookings")
public class CancelledBooking {

    @Id
    private String bookingId;

    private String reason;
    private Instant cancelledAt;

    public CancelledBooking() {
    }

    public CancelledBooking(String bookingId, String reason) {
        this.bookingId = bookingId;
        this.reason = reason;
        this.cancelledAt = Instant.now();
    }

    public String getBookingId() { return bookingId; }
    public String getReason() { return reason; }
    public Instant getCancelledAt() { return cancelledAt; }
}
