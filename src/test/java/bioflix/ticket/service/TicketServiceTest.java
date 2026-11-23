package bioflix.ticket.service;

import bioflix.ticket.dto.ScanRequest;
import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.entity.CancelledBooking;
import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.exception.ConflictException;
import bioflix.ticket.exception.ForbiddenException;
import bioflix.ticket.exception.InvalidQrTokenException;
import bioflix.ticket.exception.NotFoundException;
import bioflix.ticket.messaging.BookingConfirmed;
import bioflix.ticket.messaging.BookingConfirmed.BookedSeat;
import bioflix.ticket.messaging.EventTypes;
import bioflix.ticket.messaging.TicketIssued;
import bioflix.ticket.outbox.OutboxService;
import bioflix.ticket.repository.CancelledBookingRepository;
import bioflix.ticket.repository.TicketRepository;
import bioflix.ticket.security.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final Instant START = Instant.parse("2026-12-01T19:00:00Z");
    private static final String SHOW = "66f000000000000000000c01";
    private static final String ALICE = "11111111-1111-4111-8111-111111111111";
    private static final String BOB = "22222222-2222-4222-8222-222222222222";
    private static final Caller ADMIN = new Caller("99999999-9999-4999-8999-999999999999", "ADMIN");

    @Mock TicketRepository ticketRepository;
    @Mock CancelledBookingRepository cancelledBookingRepository;
    @Mock QrCodeGenerator qrCodeGenerator;
    @Mock OutboxService outbox;

    final QrTokenService qrTokens = new QrTokenService("test-secret-test-secret-test-secret-0000", JsonMapper.builder().build());
    TicketService service;

    /** In-memory stand-in for the tickets and cancelled_bookings collections. */
    final List<Ticket> db = new ArrayList<>();
    final Set<String> cancelled = new HashSet<>();
    final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new TicketService(ticketRepository, cancelledBookingRepository, new TicketNumberGenerator(),
                qrTokens, qrCodeGenerator, outbox);
        lenient().when(qrCodeGenerator.generatePng(anyString())).thenReturn(new byte[]{1, 2, 3});
        lenient().when(ticketRepository.insert(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", "t" + ids.incrementAndGet());
            db.add(t);
            return t;
        });
        lenient().when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ticketRepository.findByBookingId(anyString())).thenAnswer(inv ->
                db.stream().filter(t -> t.getBookingId().equals(inv.getArgument(0))).toList());
        lenient().when(ticketRepository.findById(anyString())).thenAnswer(inv ->
                db.stream().filter(t -> t.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(ticketRepository.findByTicketNumber(anyString())).thenAnswer(inv ->
                db.stream().filter(t -> t.getTicketNumber().equals(inv.getArgument(0))).findFirst());
        lenient().when(cancelledBookingRepository.existsById(anyString())).thenAnswer(inv -> cancelled.contains(inv.<String>getArgument(0)));
        lenient().when(cancelledBookingRepository.save(any(CancelledBooking.class))).thenAnswer(inv -> {
            cancelled.add(inv.<CancelledBooking>getArgument(0).getBookingId());
            return inv.getArgument(0);
        });
    }

    private static BookingConfirmed booking(String bookingId, String userId, String... seats) {
        List<BookedSeat> booked = new ArrayList<>();
        for (String s : seats) {
            booked.add(new BookedSeat(s.substring(0, 1), Integer.parseInt(s.substring(1)), "STANDARD", 13500));
        }
        return new BookingConfirmed(bookingId, userId, SHOW, "Dune", "Sal 1", START, booked);
    }

    /** Issues tickets for a booking and returns the first ticket – used to arrange tests. */
    private Ticket existingTicket(String bookingId, String userId, String... seats) {
        service.issueTickets(booking(bookingId, userId, seats), bookingId);
        return db.stream().filter(t -> t.getBookingId().equals(bookingId)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- issuing

    @Test
    void issuesOneTicketPerSeatWithNumberAndSignedToken() {
        // Arrange
        BookingConfirmed confirmed = booking("b1", ALICE, "F7", "F8");

        // Act
        List<TicketResponse> result = service.issueTickets(confirmed, "b1");

        // Assert
        assertThat(result).extracting(t -> t.seat().label()).containsExactly("F7", "F8");
        assertThat(result).allSatisfy(t -> {
            assertThat(t.status()).isEqualTo(TicketStatus.VALID);
            assertThat(t.ticketNumber()).matches("TK-....-....");
            assertThat(t.seat().type()).isEqualTo("STANDARD");
            assertThat(t.startsAt()).isEqualTo(START);
        });
        assertThat(db).allSatisfy(t ->
                assertThat(qrTokens.verify(t.getQrToken()).ticketNumber()).isEqualTo(t.getTicketNumber()));
    }

    @Test
    void queuesOneTicketIssuedWithTheDesignFields() {
        // Arrange
        BookingConfirmed confirmed = booking("b1", ALICE, "F7");
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);

        // Act
        service.issueTickets(confirmed, "corr-1");

        // Assert
        verify(outbox).enqueueOnce(eq(EventTypes.TICKET_ISSUED), eq("b1"), eq("corr-1"), data.capture());
        TicketIssued issued = (TicketIssued) data.getValue();
        assertThat(issued.bookingId()).isEqualTo("b1");
        assertThat(issued.userId()).isEqualTo(ALICE);
        assertThat(issued.tickets()).singleElement().satisfies(t -> {
            assertThat(t.row()).isEqualTo("F");
            assertThat(t.number()).isEqualTo(7);
            assertThat(t.ticketNumber()).isEqualTo(db.getFirst().getTicketNumber());
            assertThat(t.qrToken()).isEqualTo(db.getFirst().getQrToken());
        });
    }

    @Test
    void redeliveryFillsInMissingSeatsWithoutDuplicates() {
        // Arrange
        service.issueTickets(booking("b1", ALICE, "F7"), "b1");   // e.g. crashed after the first seat

        // Act
        service.issueTickets(booking("b1", ALICE, "F7", "F8"), "b1");

        // Assert
        assertThat(db).extracting(t -> t.getSeat().label()).containsExactlyInAnyOrder("F7", "F8");
    }

    @Test
    void cancelledSeatCanBeTicketedForANewBooking() {
        // Arrange
        existingTicket("b1", ALICE, "F5");
        service.voidTickets("b1", "USER");

        // Act
        List<TicketResponse> newTickets = service.issueTickets(booking("b2", BOB, "F5"), "b2");

        // Assert
        assertThat(newTickets).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo(TicketStatus.VALID));
        assertThat(db.getFirst().getStatus()).isEqualTo(TicketStatus.VOID);
    }

    @Test
    void confirmArrivingAfterCancelIssuesNothing() {
        // Arrange
        cancelled.add("b1");

        // Act
        List<TicketResponse> result = service.issueTickets(booking("b1", ALICE, "F7"), "b1");

        // Assert
        assertThat(result).isEmpty();
        assertThat(db).isEmpty();
        verify(outbox, never()).enqueueOnce(anyString(), anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------- voiding

    @Test
    void voidMarksValidTicketsVoid() {
        // Arrange
        existingTicket("b1", ALICE, "F7");

        // Act
        service.voidTickets("b1", "USER");

        // Assert
        assertThat(db.getFirst().getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(db.getFirst().getVoidedAt()).isNotNull();
    }

    @Test
    void voidLeavesScannedTicketsAlone() {
        // Arrange
        existingTicket("b1", ALICE, "F7", "F8");
        db.get(1).markScanned();

        // Act
        service.voidTickets("b1", "SHOWTIME_CANCELLED");

        // Assert
        assertThat(db.get(0).getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(db.get(1).getStatus()).isEqualTo(TicketStatus.SCANNED);
    }

    @Test
    void voidIsIdempotent() {
        // Arrange
        existingTicket("b1", ALICE, "F7");
        service.voidTickets("b1", "USER");

        // Act
        service.voidTickets("b1", "USER");

        // Assert
        verify(cancelledBookingRepository).save(any(CancelledBooking.class));   // only once
        verify(ticketRepository, times(2)).saveAll(anyIterable());
        assertThat(db.getFirst().getStatus()).isEqualTo(TicketStatus.VOID);
    }

    // ---------------------------------------------------------------- scanning

    @Test
    void scanWithValidTokenMarksTicketScanned() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        ScanRequest request = new ScanRequest(ticket.getQrToken(), SHOW);

        // Act
        TicketResponse scanned = service.scan(request, ADMIN);

        // Assert
        assertThat(scanned.status()).isEqualTo(TicketStatus.SCANNED);
        assertThat(scanned.scannedAt()).isNotNull();
    }

    @Test
    void ticketCannotBeScannedTwice() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        service.scan(new ScanRequest(ticket.getQrToken(), null), ADMIN);

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(new ScanRequest(ticket.getQrToken(), null), ADMIN));

        // Assert
        assertThat(thrown).isInstanceOf(ConflictException.class).hasMessageContaining("SCANNED");
    }

    @Test
    void customerCannotScan() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        Caller customer = new Caller(ALICE, "CUSTOMER");

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(new ScanRequest(ticket.getQrToken(), null), customer));

        // Assert
        assertThat(thrown).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void callerWithoutRoleCannotScan() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        Caller noRole = new Caller(ALICE, null);

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(new ScanRequest(ticket.getQrToken(), null), noRole));

        // Assert
        assertThat(thrown).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void forgedTokenIsRejected() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        String forged = new QrTokenService("attacker-secret-attacker-secret-0000", JsonMapper.builder().build())
                .sign(ticket.getTicketNumber(), SHOW);

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(new ScanRequest(forged, null), ADMIN));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void ticketForAnotherShowtimeIsRejected() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        ScanRequest atWrongDoor = new ScanRequest(ticket.getQrToken(), "another-showtime");

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(atWrongDoor, ADMIN));

        // Assert
        assertThat(thrown).isInstanceOf(ConflictException.class).hasMessageContaining("another showtime");
    }

    @Test
    void voidTicketCannotBeScanned() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");
        service.voidTickets("b1", "USER");

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(new ScanRequest(ticket.getQrToken(), null), ADMIN));

        // Assert
        assertThat(thrown).isInstanceOf(ConflictException.class).hasMessageContaining("VOID");
    }

    @Test
    void validlySignedTokenForUnknownTicketIsNotFound() {
        // Arrange
        String token = qrTokens.sign("TK-NOPE-NOPE", SHOW);

        // Act
        Throwable thrown = catchThrowable(() -> service.scan(new ScanRequest(token, null), ADMIN));

        // Assert
        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- queries and access

    @Test
    void ownerCanSeeTheirTicketsForABooking() {
        // Arrange
        existingTicket("b1", ALICE, "F7");

        // Act
        List<TicketResponse> tickets = service.getByBooking("b1", new Caller(ALICE, "CUSTOMER"));

        // Assert
        assertThat(tickets).singleElement().satisfies(t -> assertThat(t.seat().label()).isEqualTo("F7"));
    }

    @Test
    void adminCanSeeAnyonesBooking() {
        // Arrange
        existingTicket("b1", ALICE, "F7");

        // Act
        List<TicketResponse> tickets = service.getByBooking("b1", ADMIN);

        // Assert
        assertThat(tickets).hasSize(1);
    }

    @Test
    void otherUsersBookingIsNotFound() {
        // Arrange
        existingTicket("b1", ALICE, "F7");

        // Act
        Throwable thrown = catchThrowable(() -> service.getByBooking("b1", new Caller(BOB, null)));

        // Assert
        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherUsersTicketIsNotFound() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");

        // Act
        Throwable thrown = catchThrowable(() -> service.getById(ticket.getId(), new Caller(BOB, null)));

        // Assert
        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    void qrCodeIsDrawnFromTheSignedToken() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");

        // Act
        byte[] png = service.getQrCode(ticket.getId(), new Caller(ALICE, null));

        // Assert
        assertThat(png).isNotEmpty();
        verify(qrCodeGenerator).generatePng(ticket.getQrToken());
    }

    @Test
    void otherUsersQrCodeIsNotFound() {
        // Arrange
        Ticket ticket = existingTicket("b1", ALICE, "F7");

        // Act
        Throwable thrown = catchThrowable(() -> service.getQrCode(ticket.getId(), new Caller(BOB, null)));

        // Assert
        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    void bookingWithoutTicketsYetIsEmpty() {
        // Arrange
        Caller alice = new Caller(ALICE, null);

        // Act
        List<TicketResponse> tickets = service.getByBooking("b9", alice);

        // Assert
        assertThat(tickets).isEmpty();
    }

    @Test
    void unknownTicketIsNotFound() {
        // Arrange
        when(ticketRepository.findById("nope")).thenReturn(Optional.empty());

        // Act
        Throwable thrown = catchThrowable(() -> service.getById("nope", ADMIN));

        // Assert
        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }
}
