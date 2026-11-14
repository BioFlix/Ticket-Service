package bioflix.ticket.dto;

import java.time.Instant;
import java.util.List;

/** What ticket-service needs to issue tickets for a confirmed booking (built from booking.confirmed). */
public record IssueTicketsRequest(
        Long bookingId,
        Long userId,
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startTime,
        List<String> seats
) {
}
