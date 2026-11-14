package bioflix.ticket.messaging;

import java.time.Instant;
import java.util.List;

/**
 * Published on routing key {@code ticket.issued} when the tickets for a booking exist.
 * notification-service e-mails them. The QR image is not included – fetch it from {@code qrUrl}.
 */
public record TicketIssuedEvent(
        Long bookingId,
        Long userId,
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startTime,
        List<IssuedTicket> tickets
) {
    public record IssuedTicket(String ticketId, String seat, String qrUrl) {
    }
}
