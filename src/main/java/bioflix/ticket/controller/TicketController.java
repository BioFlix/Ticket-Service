package bioflix.ticket.controller;

import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.service.TicketService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping("/me")
    public List<TicketResponse> myTickets(@RequestHeader("X-User-Id") Long userId) {
        return ticketService.getByUser(userId);
    }

    @GetMapping("/booking/{bookingId}")
    public List<TicketResponse> byBooking(@PathVariable Long bookingId) {
        // TODO: check that the booking belongs to X-User-Id
        return ticketService.getByBooking(bookingId);
    }

    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable String id) {
        return ticketService.getById(id);
    }

    /** Returns the QR code as a PNG – can be used directly in an <img src="..."> tag. */
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrCode(@PathVariable String id) {
        return ticketService.getQrCode(id);
    }

    /** Staff scans the QR code at the door. TODO: staff/admin only. */
    @PostMapping("/{id}/scan")
    public TicketResponse scan(@PathVariable String id) {
        return ticketService.scan(id);
    }
}
