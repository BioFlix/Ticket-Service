package bioflix.ticket.config;

import bioflix.ticket.entity.CancelledBooking;
import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketSeat;
import bioflix.ticket.repository.CancelledBookingRepository;
import bioflix.ticket.repository.TicketRepository;
import bioflix.ticket.service.QrTokenService;
import bioflix.ticket.service.TicketNumberGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;

/**
 * Demo data for local development. Only runs with the "seed" profile:
 * {@code mvn spring-boot:run -Dspring-boot.run.profiles=seed}
 * <p>
 * Writes straight to MongoDB – no ticket.issued events, so no e-mails. Safe to run again:
 * bookings that already have tickets are skipped.
 */
@Component
@Profile("seed")
public class DataSeeder implements ApplicationRunner {

    // Fixed UUIDs so they're easy to use in Swagger UI / curl
    public static final String CUSTOMER_1 = "11111111-1111-4111-8111-111111111111";
    public static final String CUSTOMER_2 = "22222222-2222-4222-8222-222222222222";
    public static final String CUSTOMER_3 = "33333333-3333-4333-8333-333333333333";

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final TicketRepository ticketRepository;
    private final CancelledBookingRepository cancelledBookingRepository;
    private final TicketNumberGenerator ticketNumbers;
    private final QrTokenService qrTokens;

    public DataSeeder(TicketRepository ticketRepository, CancelledBookingRepository cancelledBookingRepository,
                      TicketNumberGenerator ticketNumbers, QrTokenService qrTokens) {
        this.ticketRepository = ticketRepository;
        this.cancelledBookingRepository = cancelledBookingRepository;
        this.ticketNumbers = ticketNumbers;
        this.qrTokens = qrTokens;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
        Instant nextWeek = now.plus(7, ChronoUnit.DAYS);
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);

        // Showtime ids look like Catalog ObjectIds
        String dune = "66f000000000000000000c01";
        String oppenheimer = "66f000000000000000000c02";
        String interstellar = "66f000000000000000000c03";

        // Customer 1: two valid tickets for tomorrow
        seed("b0000000-0000-4000-8000-000000000001", CUSTOMER_1, dune, "Dune: Part Two", "Sal 1", tomorrow,
                List.of(new TicketSeat("C", 7, "STANDARD"), new TicketSeat("C", 8, "STANDARD")), t -> { });

        // Customer 2: already watched – scanned at the door
        seed("b0000000-0000-4000-8000-000000000002", CUSTOMER_2, oppenheimer, "Oppenheimer", "Sal 2", yesterday,
                List.of(new TicketSeat("A", 1, "WHEELCHAIR")), Ticket::markScanned);

        // Customer 1 cancelled this booking (seat F5) ...
        String cancelledBooking = "b0000000-0000-4000-8000-000000000003";
        seed(cancelledBooking, CUSTOMER_1, interstellar, "Interstellar", "Sal 3", nextWeek,
                List.of(new TicketSeat("F", 5, "VIP")), Ticket::markVoid);
        if (!cancelledBookingRepository.existsById(cancelledBooking)) {
            cancelledBookingRepository.save(new CancelledBooking(cancelledBooking, "USER"));
        }
        // ... so customer 3 could book the same seat F5 afterwards
        seed("b0000000-0000-4000-8000-000000000004", CUSTOMER_3, interstellar, "Interstellar", "Sal 3", nextWeek,
                List.of(new TicketSeat("F", 5, "VIP"), new TicketSeat("F", 6, "VIP")), t -> { });
    }

    private void seed(String bookingId, String userId, String showtimeId, String movieTitle, String hallName,
                      Instant startsAt, List<TicketSeat> seats, Consumer<Ticket> finish) {
        if (!ticketRepository.findByBookingId(bookingId).isEmpty()) {
            log.info("Seed booking {} already exists – skipping", bookingId);
            return;
        }
        for (TicketSeat seat : seats) {
            String ticketNumber = ticketNumbers.next();
            Ticket ticket = new Ticket(ticketNumber, bookingId, userId, showtimeId, seat, movieTitle, hallName,
                    startsAt, qrTokens.sign(ticketNumber, showtimeId));
            finish.accept(ticket);
            ticketRepository.insert(ticket);
        }
        log.info("Seeded booking {} for user {}: {} {}", bookingId, userId, movieTitle,
                seats.stream().map(TicketSeat::label).toList());
    }
}
