package bioflix.ticket.service;

import bioflix.ticket.dto.IssueTicketsRequest;
import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.entity.CancelledBooking;
import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.exception.ConflictException;
import bioflix.ticket.exception.ForbiddenException;
import bioflix.ticket.exception.NotFoundException;
import bioflix.ticket.messaging.TicketEventPublisher;
import bioflix.ticket.repository.CancelledBookingRepository;
import bioflix.ticket.repository.TicketRepository;
import bioflix.ticket.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final CancelledBookingRepository cancelledBookingRepository;
    private final QrCodeGenerator qrCodeGenerator;
    private final TicketEventPublisher eventPublisher;

    public TicketService(TicketRepository ticketRepository, CancelledBookingRepository cancelledBookingRepository,
                         QrCodeGenerator qrCodeGenerator, TicketEventPublisher eventPublisher) {
        this.ticketRepository = ticketRepository;
        this.cancelledBookingRepository = cancelledBookingRepository;
        this.qrCodeGenerator = qrCodeGenerator;
        this.eventPublisher = eventPublisher;
    }

    // ---------------------------------------------------------------- event handlers

    /**
     * Called on booking.confirmed. One ticket (with QR code) per seat, then publishes ticket.issued.
     * Idempotent: seats that already have a ticket for this booking are skipped, so a redelivered
     * message (or one that failed halfway) just fills in what is missing.
     * A seat can only be ticketed again once the earlier ticket for it has been cancelled.
     */
    public List<TicketResponse> issueTickets(IssueTicketsRequest request) {
        Long bookingId = request.bookingId();
        if (cancelledBookingRepository.existsById(bookingId)) {
            log.warn("Booking {} is already cancelled – not issuing tickets", bookingId);
            return List.of();
        }

        Set<String> alreadyIssued = ticketRepository.findByBookingId(bookingId).stream()
                .map(Ticket::getSeat).collect(Collectors.toSet());
        List<String> missing = new LinkedHashSet<>(request.seats()).stream()
                .filter(seat -> !alreadyIssued.contains(seat)).toList();

        if (!missing.isEmpty()) {
            List<String> keys = missing.stream().map(seat -> Ticket.seatKey(request.showtimeId(), seat)).toList();
            List<Ticket> taken = ticketRepository.findBySeatKeyIn(keys);
            if (!taken.isEmpty()) {
                // Usually the cancellation of the previous booking just hasn't been processed yet – the
                // listener retries, and dead-letters the message if the seat stays taken.
                throw new ConflictException("Seats already have an active ticket: "
                        + taken.stream().map(Ticket::getSeat).toList());
            }
            missing.forEach(seat -> issueOne(request, seat));
        } else {
            log.info("Tickets for booking {} already issued", bookingId);
        }

        // booking.cancelled may have been handled while we were issuing
        if (cancelledBookingRepository.existsById(bookingId)) {
            cancelTickets(bookingId);
            return List.of();
        }

        List<Ticket> tickets = ticketRepository.findByBookingId(bookingId);
        // Sent on every successful run (at-least-once), so a lost publish is fixed by redelivery
        eventPublisher.publishTicketsIssued(tickets);
        return tickets.stream().map(TicketResponse::from).toList();
    }

    private Ticket issueOne(IssueTicketsRequest request, String seat) {
        Ticket ticket;
        try {
            ticket = ticketRepository.save(new Ticket(request.bookingId(), request.userId(), request.showtimeId(),
                    request.movieTitle(), request.hallName(), request.startTime(), seat));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("Seat " + seat + " already has an active ticket");
        }

        // The id only exists after the first save, so the QR is added afterwards
        String payload = "CINEMA-TICKET:" + ticket.getId();
        ticket.setQrPayload(payload);
        ticket.setQrCodePng(qrCodeGenerator.generatePng(payload));
        return ticketRepository.save(ticket);
    }

    /**
     * Called on booking.cancelled. Voids the valid tickets and frees their seats so a new booking can
     * get tickets for them. Used tickets are left as they are. Idempotent.
     */
    public void cancelTickets(Long bookingId) {
        if (!cancelledBookingRepository.existsById(bookingId)) {
            cancelledBookingRepository.save(new CancelledBooking(bookingId));
        }
        List<Ticket> valid = ticketRepository.findByBookingId(bookingId).stream()
                .filter(t -> t.getStatus() == TicketStatus.VALID).toList();
        valid.forEach(Ticket::cancel);
        ticketRepository.saveAll(valid);
        log.info("Cancelled {} ticket(s) for booking {}", valid.size(), bookingId);
    }

    // ---------------------------------------------------------------- commands

    /** Scanned by staff at the cinema entrance. */
    public TicketResponse scan(String ticketId, Caller caller) {
        if (!caller.isStaff()) {
            throw new ForbiddenException("Only staff can scan tickets");
        }
        Ticket ticket = findOrThrow(ticketId);
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ConflictException("Ticket is " + ticket.getStatus());
        }
        ticket.markUsed();
        return TicketResponse.from(ticketRepository.save(ticket));
    }

    // ---------------------------------------------------------------- queries

    /** Tickets for a booking. Another user's booking looks like an unknown one (404). */
    public List<TicketResponse> getByBooking(Long bookingId, Caller caller) {
        List<Ticket> tickets = ticketRepository.findByBookingId(bookingId);
        if (!tickets.isEmpty() && !caller.canSee(tickets.getFirst().getUserId())) {
            throw new NotFoundException("Booking " + bookingId + " not found");
        }
        return tickets.stream().map(TicketResponse::from).toList();
    }

    public List<TicketResponse> getByUser(Long userId) {
        return ticketRepository.findByUserIdOrderByStartTimeDesc(userId).stream().map(TicketResponse::from).toList();
    }

    public TicketResponse getById(String id, Caller caller) {
        return TicketResponse.from(findVisible(id, caller));
    }

    public byte[] getQrCode(String id, Caller caller) {
        return findVisible(id, caller).getQrCodePng();
    }

    private Ticket findVisible(String id, Caller caller) {
        Ticket ticket = findOrThrow(id);
        if (!caller.canSee(ticket.getUserId())) {
            throw new NotFoundException("Ticket " + id + " not found");   // don't reveal other users' tickets
        }
        return ticket;
    }

    private Ticket findOrThrow(String id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket " + id + " not found"));
    }
}
