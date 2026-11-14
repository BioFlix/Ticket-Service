package bioflix.ticket.repository;

import bioflix.ticket.entity.CancelledBooking;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CancelledBookingRepository extends MongoRepository<CancelledBooking, Long> {
}
