package bioflix.ticket.repository;

import bioflix.ticket.entity.Ticket;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TicketRepository extends MongoRepository<Ticket, String> {

    List<Ticket> findByBookingId(Long bookingId);

    List<Ticket> findByUserIdOrderByStartTimeDesc(Long userId);

    boolean existsByBookingId(Long bookingId);
}
