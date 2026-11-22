package bioflix.ticket.service;

import bioflix.ticket.dto.ScanRequest;
import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.entity.CancelledBooking;
import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketSeat;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.exception.ConflictException;
import bioflix.ticket.exception.ForbiddenException;
import bioflix.ticket.exception.NotFoundException;
import bioflix.ticket.messaging.BookingConfirmed;
import bioflix.ticket.messaging.EventTypes;
import bioflix.ticket.messaging.TicketIssued;
import bioflix.ticket.outbox.OutboxService;
import bioflix.ticket.repository.CancelledBookingRepository;
import bioflix.ticket.repository.TicketRepository;
import bioflix.ticket.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private static final int MAX_TICKET_NUMBER_ATTEMPTS = 5;

    private final TicketRepository ticketRepository;
    private final CancelledBookingRepository cancelledBookingRepository;
    private final TicketNumberGenerator ticketNumbers;
    private final QrTokenService qrTokens;
    private final QrCodeGenerator qrCodeGenerator;
    private final OutboxService outbox;

    public TicketService(TicketRepository ticketRepository, CancelledBookingRepository cancelledBookingRepository,
                         TicketNumberGenerator ticketNumbers, QrTokenService qrTokens,
                         QrCodeGenerator qrCodeGenerator, OutboxService outbox) {
        this.ticketRepository = ticketRepository;
        this.cancelledBookingRepository = cancelledBookingRepository;
        this.ticketNumbers = ticketNumbers;
        this.qrTokens = qrTokens;
        this.qrCodeGenerator = qrCodeGenerator;
        this.outbox = outbox;
    }

    // ---------------------------------------------------------------- event handlers

    /**
     * booking.confirmed: one VALID ticket per booked seat, then ticket.issued into the outbox.
     * Idempotent – seats that already have a ticket for this booking are skipped, and ticket.issued
     * is only queued once per booking.
     */
    public List<TicketResponse> issueTickets(BookingConfirmed booking, String correlationId) {
        String bookingId = booking.bookingId();
        if (cancelledBookingRepository.existsById(bookingId)) {
            log.warn("Booking {} is already cancelled – not issuing tickets", bookingId);
            return List.of();
        }

        Set<String> alreadyIssued = ticketRepository.findByBookingId(bookingId).stream()
                .map(t -> t.getSeat().label()).collect(Collectors.toSet());
        for (BookingConfirmed.BookedSeat bookedSeat : booking.seats()) {
            TicketSeat seat = new TicketSeat(bookedSeat.row(), bookedSeat.number(), bookedSeat.type());
            if (alreadyIssued.add(seat.label())) {
                issueOne(booking, seat);
            }
        }

        // booking.cancelled may have been handled while we were issuing
        if (cancelledBookingRepository.existsById(bookingId)) {
            voidValidTickets(bookingId);
            return List.of();
        }

        List<Ticket> tickets = sorted(ticketRepository.findByBookingId(bookingId));
        TicketIssued issued = new TicketIssued(bookingId, booking.userId(), tickets.stream()
                .map(t -> new TicketIssued.IssuedTicket(t.getTicketNumber(), t.getSeat().row(), t.getSeat().number(), t.getQrToken()))
                .toList());
        outbox.enqueueOnce(EventTypes.TICKET_ISSUED, bookingId, correlationId != null ? correlationId : bookingId, issued);
        return tickets.stream().map(TicketResponse::from).toList();
    }

    private void issueOne(BookingConfirmed booking, TicketSeat seat) {
        for (int attempt = 1; ; attempt++) {
            String ticketNumber = ticketNumbers.next();
            Ticket ticket = new Ticket(ticketNumber, booking.bookingId(), booking.userId(), booking.showtimeId(), seat,
                    booking.movieTitle(), booking.hallName(), booking.startsAt(),
                    qrTokens.sign(ticketNumber, booking.showtimeId()));
            try {
                ticketRepository.insert(ticket);
                return;
            } catch (DuplicateKeyException ex) {
                if (ticketRepository.findByBookingId(booking.bookingId()).stream()
                        .anyMatch(t -> t.getSeat().label().equals(seat.label()))) {
                    return;   // issued concurrently by a redelivery – nothing to do
                }
                if (attempt >= MAX_TICKET_NUMBER_ATTEMPTS) {
                    throw new IllegalStateException("Could not generate a unique ticket number", ex);
                }
                // ticketNumber collision – try another one
            }
        }
    }

    /** booking.cancelled: VALID tickets become VOID. Scanned tickets are left as they are. Idempotent. */
    public void voidTickets(String bookingId, String reason) {
        if (!cancelledBookingRepository.existsById(bookingId)) {
            cancelledBookingRepository.save(new CancelledBooking(bookingId, reason));
        }
        voidValidTickets(bookingId);
    }

    private void voidValidTickets(String bookingId) {
        List<Ticket> valid = ticketRepository.findByBookingId(bookingId).stream()
                .filter(t -> t.getStatus() == TicketStatus.VALID).toList();
        valid.forEach(Ticket::markVoid);
        ticketRepository.saveAll(valid);
        log.info("Voided {} ticket(s) for booking {}", valid.size(), bookingId);
    }

    // ---------------------------------------------------------------- commands

    /** At the door: the scanner sends the token from the QR code. Admins only. */
    public TicketResponse scan(ScanRequest request, Caller caller) {
        if (!caller.isAdmin()) {
            throw new ForbiddenException("Only admins can scan tickets");
        }
        QrTokenService.Claims claims = qrTokens.verify(request.qrToken());
        Ticket ticket = ticketRepository.findByTicketNumber(claims.ticketNumber())
                .orElseThrow(() -> new NotFoundException("Ticket " + claims.ticketNumber() + " not found"));
        if (!ticket.getShowtimeId().equals(claims.showtimeId()) || !ticket.getQrToken().equals(request.qrToken().trim())) {
            throw new ConflictException("QR code does not match ticket " + ticket.getTicketNumber());
        }
        if (request.showtimeId() != null && !request.showtimeId().isBlank()
                && !request.showtimeId().equals(ticket.getShowtimeId())) {
            throw new ConflictException("Ticket " + ticket.getTicketNumber() + " is for another showtime");
        }
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ConflictException("Ticket " + ticket.getTicketNumber() + " is " + ticket.getStatus());
        }
        ticket.markScanned();
        return TicketResponse.from(ticketRepository.save(ticket));
    }

    // ---------------------------------------------------------------- queries

    /** Tickets for a booking. Another user's booking looks like an unknown one (404). */
    public List<TicketResponse> getByBooking(String bookingId, Caller caller) {
        List<Ticket> tickets = sorted(ticketRepository.findByBookingId(bookingId));
        if (!tickets.isEmpty() && !caller.canSee(tickets.getFirst().getUserId())) {
            throw new NotFoundException("Booking " + bookingId + " not found");
        }
        return tickets.stream().map(TicketResponse::from).toList();
    }

    public List<TicketResponse> getByUser(String userId) {
        return ticketRepository.findByUserIdOrderByStartsAtDesc(userId).stream().map(TicketResponse::from).toList();
    }

    public TicketResponse getById(String id, Caller caller) {
        return TicketResponse.from(findVisible(id, caller));
    }

    /** The QR image is drawn on request from the signed token – it isn't stored. */
    public byte[] getQrCode(String id, Caller caller) {
        return qrCodeGenerator.generatePng(findVisible(id, caller).getQrToken());
    }

    private Ticket findVisible(String id, Caller caller) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket " + id + " not found"));
        if (!caller.canSee(ticket.getUserId())) {
            throw new NotFoundException("Ticket " + id + " not found");   // don't reveal other users' tickets
        }
        return ticket;
    }

    private static List<Ticket> sorted(List<Ticket> tickets) {
        return tickets.stream()
                .sorted(Comparator.comparing((Ticket t) -> t.getSeat().row()).thenComparingInt(t -> t.getSeat().number()))
                .toList();
    }
}
