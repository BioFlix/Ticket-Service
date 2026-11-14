package bioflix.ticket.config;

import bioflix.ticket.entity.CancelledBooking;
import bioflix.ticket.entity.Ticket;
import bioflix.ticket.repository.CancelledBookingRepository;
import bioflix.ticket.repository.TicketRepository;
import bioflix.ticket.service.QrCodeGenerator;
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
 * Writes straight to MongoDB (no ticket.issued events). Safe to run again – bookings that
 * already have tickets are skipped.
 */
@Component
@Profile("seed")
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final TicketRepository ticketRepository;
    private final CancelledBookingRepository cancelledBookingRepository;
    private final QrCodeGenerator qrCodeGenerator;

    public DataSeeder(TicketRepository ticketRepository, CancelledBookingRepository cancelledBookingRepository,
                      QrCodeGenerator qrCodeGenerator) {
        this.ticketRepository = ticketRepository;
        this.cancelledBookingRepository = cancelledBookingRepository;
        this.qrCodeGenerator = qrCodeGenerator;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
        Instant nextWeek = now.plus(7, ChronoUnit.DAYS);
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);

        // User 1: two valid tickets for tomorrow
        seed(1001L, 1L, "seed-showtime-dune", "Dune: Part Two", "Sal 1", tomorrow, List.of("C7", "C8"), t -> { });

        // User 2: already watched – ticket was scanned at the door
        seed(1002L, 2L, "seed-showtime-oppenheimer", "Oppenheimer", "Sal 2", yesterday, List.of("A1"), Ticket::markUsed);

        // User 1 cancelled booking 1003 (seat F5) ...
        seed(1003L, 1L, "seed-showtime-interstellar", "Interstellar", "Sal 3", nextWeek, List.of("F5"), Ticket::cancel);
        if (!cancelledBookingRepository.existsById(1003L)) {
            cancelledBookingRepository.save(new CancelledBooking(1003L));
        }
        // ... so user 3 could book the same seat F5 afterwards
        seed(1004L, 3L, "seed-showtime-interstellar", "Interstellar", "Sal 3", nextWeek, List.of("F5", "F6"), t -> { });
    }

    private void seed(Long bookingId, Long userId, String showtimeId, String movieTitle, String hallName,
                      Instant startTime, List<String> seats, Consumer<Ticket> finish) {
        if (!ticketRepository.findByBookingId(bookingId).isEmpty()) {
            log.info("Seed booking {} already exists – skipping", bookingId);
            return;
        }
        for (String seat : seats) {
            Ticket ticket = ticketRepository.save(
                    new Ticket(bookingId, userId, showtimeId, movieTitle, hallName, startTime, seat));
            String payload = "CINEMA-TICKET:" + ticket.getId();
            ticket.setQrPayload(payload);
            ticket.setQrCodePng(qrCodeGenerator.generatePng(payload));
            finish.accept(ticket);
            ticketRepository.save(ticket);
        }
        log.info("Seeded booking {} for user {}: {} {}", bookingId, userId, movieTitle, seats);
    }
}
