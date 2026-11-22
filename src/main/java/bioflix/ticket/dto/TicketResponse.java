package bioflix.ticket.dto;

import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketSeat;
import bioflix.ticket.entity.TicketStatus;

import java.time.Instant;

/** The QR image is served from qrUrl (image/png). The raw qrToken is not exposed in the API. */
public record TicketResponse(
        String id,
        String ticketNumber,
        String bookingId,
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startsAt,
        TicketSeat seat,
        TicketStatus status,
        String qrUrl,
        Instant issuedAt,
        Instant scannedAt,
        Instant voidedAt
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(t.getId(), t.getTicketNumber(), t.getBookingId(), t.getShowtimeId(),
                t.getMovieTitle(), t.getHallName(), t.getStartsAt(), t.getSeat(), t.getStatus(),
                Ticket.qrUrl(t.getId()), t.getIssuedAt(), t.getScannedAt(), t.getVoidedAt());
    }
}
