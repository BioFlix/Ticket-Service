package bioflix.ticket.repository;

import bioflix.ticket.entity.Ticket;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends MongoRepository<Ticket, String> {

    List<Ticket> findByBookingId(String bookingId);

    List<Ticket> findByUserIdOrderByStartsAtDesc(String userId);

    Optional<Ticket> findByTicketNumber(String ticketNumber);
}
