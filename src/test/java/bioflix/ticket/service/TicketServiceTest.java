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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final Instant START = Instant.parse("2026-12-01T19:00:00Z");

    @Mock TicketRepository ticketRepository;
    @Mock CancelledBookingRepository cancelledBookingRepository;
    @Mock QrCodeGenerator qrCodeGenerator;
    @Mock TicketEventPublisher eventPublisher;

    TicketService service;

    /** Simple in-memory stand-in for the tickets collection. */
    final List<Ticket> db = new ArrayList<>();
    final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new TicketService(ticketRepository, cancelledBookingRepository, qrCodeGenerator, eventPublisher);
        lenient().when(qrCodeGenerator.generatePng(anyString())).thenReturn(new byte[]{1, 2, 3});
        lenient().when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            if (t.getId() == null) {
                ReflectionTestUtils.setField(t, "id", "t" + ids.incrementAndGet());
                db.add(t);
            }
            return t;
        });
        lenient().when(ticketRepository.findByBookingId(any())).thenAnswer(inv ->
                db.stream().filter(t -> t.getBookingId().equals(inv.getArgument(0))).toList());
        lenient().when(ticketRepository.findBySeatKeyIn(anyList())).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(0);
            return db.stream().filter(t -> t.getSeatKey() != null && keys.contains(t.getSeatKey())).toList();
        });
        lenient().when(ticketRepository.findById(anyString())).thenAnswer(inv ->
                db.stream().filter(t -> t.getId().equals(inv.getArgument(0))).findFirst());
    }

    private static IssueTicketsRequest request(long bookingId, long userId, String... seats) {
        return new IssueTicketsRequest(bookingId, userId, "show-1", "Dune", "Sal 1", START, List.of(seats));
    }

    @Test
    void issuesOneTicketWithQrPerSeatAndPublishes() {
        List<TicketResponse> result = service.issueTickets(request(1, 10, "C7", "C8"));

        assertThat(result).extracting(TicketResponse::seat).containsExactly("C7", "C8");
        assertThat(result).allSatisfy(t -> {
            assertThat(t.status()).isEqualTo(TicketStatus.VALID);
            assertThat(t.qrUrl()).isEqualTo("/api/tickets/" + t.id() + "/qr");
        });
        assertThat(db).allSatisfy(t -> {
            assertThat(t.getQrPayload()).isEqualTo("CINEMA-TICKET:" + t.getId());
            assertThat(t.getQrCodePng()).isNotEmpty();
            assertThat(t.getSeatKey()).isEqualTo("show-1|" + t.getSeat());
        });
        verify(eventPublisher).publishTicketsIssued(anyList());
    }

    @Test
    void duplicateSeatsInRequestGiveOneTicket() {
        assertThat(service.issueTickets(request(1, 10, "C7", "C7"))).hasSize(1);
    }

    @Test
    void redeliveryDoesNotDuplicateAndFillsInMissingSeats() {
        service.issueTickets(request(1, 10, "C7"));            // e.g. crashed after the first seat
        List<TicketResponse> again = service.issueTickets(request(1, 10, "C7", "C8"));

        assertThat(again).extracting(TicketResponse::seat).containsExactlyInAnyOrder("C7", "C8");
        assertThat(db).hasSize(2);
    }

    @Test
    void seatWithActiveTicketFromAnotherBookingIsAConflict() {
        service.issueTickets(request(1, 10, "C7"));

        assertThatThrownBy(() -> service.issueTickets(request(2, 20, "C7", "C8")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("C7");
        assertThat(db).hasSize(1);   // nothing half-issued for booking 2
    }

    @Test
    void cancelledSeatCanBeTicketedForANewBooking() {
        service.issueTickets(request(1, 10, "C7"));
        service.cancelTickets(1L);

        List<TicketResponse> newTickets = service.issueTickets(request(2, 20, "C7"));

        assertThat(newTickets).singleElement().satisfies(t -> {
            assertThat(t.bookingId()).isEqualTo(2L);
            assertThat(t.status()).isEqualTo(TicketStatus.VALID);
        });
        Ticket old = db.getFirst();
        assertThat(old.getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(old.getSeatKey()).isNull();
        assertThat(old.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelRemembersBookingAndLeavesUsedTicketsAlone() {
        service.issueTickets(request(1, 10, "C7", "C8"));
        db.get(1).markUsed();

        service.cancelTickets(1L);

        verify(cancelledBookingRepository).save(any(CancelledBooking.class));
        assertThat(db.get(0).getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(db.get(1).getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void confirmArrivingAfterCancelIssuesNothing() {
        when(cancelledBookingRepository.existsById(1L)).thenReturn(true);

        assertThat(service.issueTickets(request(1, 10, "C7"))).isEmpty();
        assertThat(db).isEmpty();
        verify(eventPublisher, never()).publishTicketsIssued(anyList());
    }

    @Test
    void raceOnUniqueIndexBecomesConflict() {
        when(ticketRepository.save(any(Ticket.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.issueTickets(request(1, 10, "C7")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyStaffCanScanAndOnlyOnce() {
        String id = service.issueTickets(request(1, 10, "C7")).getFirst().id();

        assertThatThrownBy(() -> service.scan(id, new Caller(10L, null))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.scan(id, new Caller(10L, "CUSTOMER"))).isInstanceOf(ForbiddenException.class);

        assertThat(service.scan(id, new Caller(99L, "staff")).status()).isEqualTo(TicketStatus.USED);
        assertThatThrownBy(() -> service.scan(id, new Caller(99L, "ADMIN")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("USED");
    }

    @Test
    void cancelledTicketCannotBeScanned() {
        String id = service.issueTickets(request(1, 10, "C7")).getFirst().id();
        service.cancelTickets(1L);

        assertThatThrownBy(() -> service.scan(id, new Caller(99L, "STAFF")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("CANCELLED");
    }

    @Test
    void usersOnlySeeTheirOwnTicketsButStaffSeeAll() {
        String id = service.issueTickets(request(1, 10, "C7")).getFirst().id();

        assertThat(service.getByBooking(1L, new Caller(10L, null))).hasSize(1);
        assertThat(service.getById(id, new Caller(10L, null)).seat()).isEqualTo("C7");
        assertThat(service.getQrCode(id, new Caller(10L, null))).isNotEmpty();
        assertThat(service.getByBooking(1L, new Caller(99L, "STAFF"))).hasSize(1);

        Caller other = new Caller(20L, null);
        assertThatThrownBy(() -> service.getByBooking(1L, other)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getById(id, other)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getQrCode(id, other)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void bookingWithoutTicketsYetIsEmpty() {
        assertThat(service.getByBooking(5L, new Caller(10L, null))).isEmpty();
    }

    @Test
    void unknownTicketIsNotFound() {
        when(ticketRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById("nope", new Caller(1L, "ADMIN"))).isInstanceOf(NotFoundException.class);
    }
}
