package bioflix.ticket.dto;

import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketStatus;

import java.time.Instant;

/** The QR image itself is served separately from GET /api/tickets/{id}/qr (image/png). */
public record TicketResponse(
        String id,
        Long bookingId,
        String movieTitle,
        String hallName,
        Instant startTime,
        String seat,
        TicketStatus status,
        String qrUrl,
        Instant issuedAt,
        Instant usedAt
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(t.getId(), t.getBookingId(), t.getMovieTitle(), t.getHallName(),
                t.getStartTime(), t.getSeat(), t.getStatus(), "/api/tickets/" + t.getId() + "/qr",
                t.getIssuedAt(), t.getUsedAt());
    }
}
