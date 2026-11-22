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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final Instant START = Instant.parse("2026-12-01T19:00:00Z");
    private static final String SHOW = "66f000000000000000000c01";
    private static final String ALICE = "11111111-1111-4111-8111-111111111111";
    private static final String BOB = "22222222-2222-4222-8222-222222222222";

    @Mock TicketRepository ticketRepository;
    @Mock CancelledBookingRepository cancelledBookingRepository;
    @Mock QrCodeGenerator qrCodeGenerator;
    @Mock OutboxService outbox;

    final QrTokenService qrTokens = new QrTokenService("test-secret-test-secret-test-secret-0000", JsonMapper.builder().build());
    TicketService service;

    /** In-memory stand-in for the tickets collection. */
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

    private static final Caller ADMIN = new Caller("99999999-9999-4999-8999-999999999999", "ADMIN");

    @Test
    void issuesOneTicketPerSeatWithNumberAndSignedToken() {
        List<TicketResponse> result = service.issueTickets(booking("b1", ALICE, "F7", "F8"), "b1");

        assertThat(result).extracting(t -> t.seat().label()).containsExactly("F7", "F8");
        assertThat(result).allSatisfy(t -> {
            assertThat(t.status()).isEqualTo(TicketStatus.VALID);
            assertThat(t.ticketNumber()).matches("TK-....-....");
            assertThat(t.seat().type()).isEqualTo("STANDARD");
            assertThat(t.startsAt()).isEqualTo(START);
        });
        assertThat(db).allSatisfy(t -> assertThat(qrTokens.verify(t.getQrToken()).ticketNumber()).isEqualTo(t.getTicketNumber()));
    }

    @Test
    void queuesOneTicketIssuedWithTheDesignFields() {
        service.issueTickets(booking("b1", ALICE, "F7"), "corr-1");

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
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
        service.issueTickets(booking("b1", ALICE, "F7"), "b1");     // e.g. crashed after the first seat
        service.issueTickets(booking("b1", ALICE, "F7", "F8"), "b1");

        assertThat(db).extracting(t -> t.getSeat().label()).containsExactlyInAnyOrder("F7", "F8");
    }

    @Test
    void cancelledSeatCanBeTicketedForANewBooking() {
        service.issueTickets(booking("b1", ALICE, "F5"), "b1");
        service.voidTickets("b1", "USER");

        List<TicketResponse> newTickets = service.issueTickets(booking("b2", BOB, "F5"), "b2");

        assertThat(newTickets).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo(TicketStatus.VALID));
        assertThat(db.getFirst().getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(db.getFirst().getVoidedAt()).isNotNull();
    }

    @Test
    void voidLeavesScannedTicketsAlone() {
        service.issueTickets(booking("b1", ALICE, "F7", "F8"), "b1");
        db.get(1).markScanned();

        service.voidTickets("b1", "SHOWTIME_CANCELLED");

        assertThat(db.get(0).getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(db.get(1).getStatus()).isEqualTo(TicketStatus.SCANNED);
    }

    @Test
    void confirmArrivingAfterCancelIssuesNothing() {
        cancelled.add("b1");

        assertThat(service.issueTickets(booking("b1", ALICE, "F7"), "b1")).isEmpty();
        assertThat(db).isEmpty();
        verify(outbox, never()).enqueueOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    void scanWithValidTokenMarksScannedOnce() {
        service.issueTickets(booking("b1", ALICE, "F7"), "b1");
        String token = db.getFirst().getQrToken();

        TicketResponse scanned = service.scan(new ScanRequest(token, SHOW), ADMIN);
        assertThat(scanned.status()).isEqualTo(TicketStatus.SCANNED);
        assertThat(scanned.scannedAt()).isNotNull();

        assertThatThrownBy(() -> service.scan(new ScanRequest(token, null), ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("SCANNED");
    }

    @Test
    void onlyAdminsCanScan() {
        service.issueTickets(booking("b1", ALICE, "F7"), "b1");
        String token = db.getFirst().getQrToken();

        assertThatThrownBy(() -> service.scan(new ScanRequest(token, null), new Caller(ALICE, null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.scan(new ScanRequest(token, null), new Caller(ALICE, "CUSTOMER")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.scan(new ScanRequest(token, null), new Caller(ALICE, "STAFF")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void forgedOrWrongShowtimeOrVoidTicketsAreRejectedAtTheDoor() {
        service.issueTickets(booking("b1", ALICE, "F7"), "b1");
        Ticket ticket = db.getFirst();

        String forged = new QrTokenService("attacker-secret-attacker-secret-0000", JsonMapper.builder().build())
                .sign(ticket.getTicketNumber(), SHOW);
        assertThatThrownBy(() -> service.scan(new ScanRequest(forged, null), ADMIN))
                .isInstanceOf(InvalidQrTokenException.class);

        assertThatThrownBy(() -> service.scan(new ScanRequest(ticket.getQrToken(), "another-showtime"), ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("another showtime");

        service.voidTickets("b1", "USER");
        assertThatThrownBy(() -> service.scan(new ScanRequest(ticket.getQrToken(), null), ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("VOID");
    }

    @Test
    void validlySignedTokenForUnknownTicketIs404() {
        assertThatThrownBy(() -> service.scan(new ScanRequest(qrTokens.sign("TK-NOPE-NOPE", SHOW), null), ADMIN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void usersOnlySeeTheirOwnTicketsButAdminsSeeAll() {
        String id = service.issueTickets(booking("b1", ALICE, "F7"), "b1").getFirst().id();

        Caller alice = new Caller(ALICE, "CUSTOMER");
        assertThat(service.getByBooking("b1", alice)).hasSize(1);
        assertThat(service.getById(id, alice).seat().label()).isEqualTo("F7");
        assertThat(service.getQrCode(id, alice)).isNotEmpty();
        verify(qrCodeGenerator).generatePng(db.getFirst().getQrToken());
        assertThat(service.getByBooking("b1", ADMIN)).hasSize(1);

        Caller bob = new Caller(BOB, null);
        assertThatThrownBy(() -> service.getByBooking("b1", bob)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getById(id, bob)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getQrCode(id, bob)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void bookingWithoutTicketsYetIsEmpty() {
        assertThat(service.getByBooking("b9", new Caller(ALICE, null))).isEmpty();
    }

    @Test
    void voidIsIdempotent() {
        service.issueTickets(booking("b1", ALICE, "F7"), "b1");
        service.voidTickets("b1", "USER");
        service.voidTickets("b1", "USER");

        verify(cancelledBookingRepository).save(any(CancelledBooking.class));   // only once
        verify(ticketRepository, org.mockito.Mockito.times(2)).saveAll(anyIterable());
        assertThat(db.getFirst().getStatus()).isEqualTo(TicketStatus.VOID);
    }

    @Test
    void unknownTicketIsNotFound() {
        when(ticketRepository.findById("nope")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.getById("nope", ADMIN)).isInstanceOf(NotFoundException.class);
    }
}
