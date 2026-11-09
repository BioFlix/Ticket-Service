package bioflix.ticket.service;

import bioflix.ticket.dto.IssueTicketsRequest;
import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.exception.ConflictException;
import bioflix.ticket.exception.NotFoundException;
import bioflix.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final QrCodeGenerator qrCodeGenerator;

    public TicketService(TicketRepository ticketRepository, QrCodeGenerator qrCodeGenerator) {
        this.ticketRepository = ticketRepository;
        this.qrCodeGenerator = qrCodeGenerator;
    }

    /**
     * One ticket (with QR code) per seat. Idempotent – does nothing if already issued.
     * TODO (RabbitMQ): call this from a listener on booking.confirmed
     */
    public List<TicketResponse> issueTickets(IssueTicketsRequest request) {
        if (ticketRepository.existsByBookingId(request.bookingId())) {
            log.info("Tickets for booking {} already issued", request.bookingId());
            return getByBooking(request.bookingId());
        }

        List<Ticket> tickets = new ArrayList<>();
        for (String seat : request.seats()) {
            Ticket ticket = ticketRepository.save(new Ticket(request.bookingId(), request.userId(),
                    request.showtimeId(), request.movieTitle(), request.hallName(), request.startTime(), seat));

            // The id only exists after the first save, so the QR is added afterwards
            String payload = "CINEMA-TICKET:" + ticket.getId();
            ticket.setQrPayload(payload);
            ticket.setQrCodePng(qrCodeGenerator.generatePng(payload));
            tickets.add(ticketRepository.save(ticket));
        }

        // TODO (RabbitMQ): publish ticket.issued – notification e-mails the tickets
        return tickets.stream().map(TicketResponse::from).toList();
    }

    /**
     * Voids the tickets for a cancelled booking.
     * TODO (RabbitMQ): call this from a listener on booking.cancelled
     */
    public void cancelTickets(Long bookingId) {
        List<Ticket> tickets = ticketRepository.findByBookingId(bookingId);
        tickets.forEach(Ticket::cancel);
        ticketRepository.saveAll(tickets);
    }

    /** Scanned at the cinema entrance. */
    public TicketResponse scan(String ticketId) {
        Ticket ticket = findOrThrow(ticketId);
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ConflictException("Ticket is " + ticket.getStatus());
        }
        ticket.markUsed();
        return TicketResponse.from(ticketRepository.save(ticket));
    }

    public List<TicketResponse> getByBooking(Long bookingId) {
        return ticketRepository.findByBookingId(bookingId).stream().map(TicketResponse::from).toList();
    }

    public List<TicketResponse> getByUser(Long userId) {
        return ticketRepository.findByUserIdOrderByStartTimeDesc(userId).stream().map(TicketResponse::from).toList();
    }

    public TicketResponse getById(String id) {
        return TicketResponse.from(findOrThrow(id));
    }

    public byte[] getQrCode(String id) {
        return findOrThrow(id).getQrCodePng();
    }

    private Ticket findOrThrow(String id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket " + id + " not found"));
    }
}
