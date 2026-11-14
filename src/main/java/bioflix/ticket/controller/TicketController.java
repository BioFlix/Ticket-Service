package bioflix.ticket.controller;

import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.security.Caller;
import bioflix.ticket.service.TicketService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * X-User-Id and X-User-Role are set by the API gateway after it has validated the token.
 * Tickets are created and cancelled only through RabbitMQ events (see BookingEventListener).
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final String USER_ID = "X-User-Id";
    private static final String USER_ROLE = "X-User-Role";

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping("/me")
    public List<TicketResponse> myTickets(@RequestHeader(USER_ID) Long userId) {
        return ticketService.getByUser(userId);
    }

    @GetMapping("/booking/{bookingId}")
    public List<TicketResponse> byBooking(@RequestHeader(USER_ID) Long userId,
                                          @RequestHeader(value = USER_ROLE, required = false) String role,
                                          @PathVariable Long bookingId) {
        return ticketService.getByBooking(bookingId, new Caller(userId, role));
    }

    @GetMapping("/{id}")
    public TicketResponse getById(@RequestHeader(USER_ID) Long userId,
                                  @RequestHeader(value = USER_ROLE, required = false) String role,
                                  @PathVariable String id) {
        return ticketService.getById(id, new Caller(userId, role));
    }

    /** Returns the QR code as a PNG – can be used directly in an <img src="..."> tag. */
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrCode(@RequestHeader(USER_ID) Long userId,
                         @RequestHeader(value = USER_ROLE, required = false) String role,
                         @PathVariable String id) {
        return ticketService.getQrCode(id, new Caller(userId, role));
    }

    /** Staff scans the QR code at the door (X-User-Role STAFF or ADMIN). */
    @PostMapping("/{id}/scan")
    public TicketResponse scan(@RequestHeader(USER_ID) Long userId,
                               @RequestHeader(value = USER_ROLE, required = false) String role,
                               @PathVariable String id) {
        return ticketService.scan(id, new Caller(userId, role));
    }
}
