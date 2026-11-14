package bioflix.ticket.repository;

import bioflix.ticket.entity.Ticket;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface TicketRepository extends MongoRepository<Ticket, String> {

    List<Ticket> findByBookingId(Long bookingId);

    List<Ticket> findByUserIdOrderByStartTimeDesc(Long userId);

    /** Tickets that currently hold one of these seats (cancelled tickets have no seat key). */
    List<Ticket> findBySeatKeyIn(Collection<String> seatKeys);
}
