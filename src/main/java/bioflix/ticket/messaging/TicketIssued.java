package bioflix.ticket.messaging;

import java.util.List;

/** data of ticket.issued – one event per booking (consumer: notification-service). */
public record TicketIssued(
        String bookingId,
        String userId,
        List<IssuedTicket> tickets
) {
    public record IssuedTicket(String ticketNumber, String row, int number, String qrToken) {
    }
}
