package bioflix.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sent by the door scanner. showtimeId is optional: when given, a ticket for another showtime is rejected.
 */
public record ScanRequest(@NotBlank String qrToken, String showtimeId) {
}
