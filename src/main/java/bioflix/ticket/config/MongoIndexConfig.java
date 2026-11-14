package bioflix.ticket.config;

import bioflix.ticket.entity.Ticket;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Database-level guarantee that a seat in a showtime has at most one active ticket.
 * Partial index: cancelled tickets have no seatKey, so they don't block a new booking of the seat.
 * (Annotations can't express a partial index.) Created at startup, before the RabbitMQ listeners start.
 */
@Component
public class MongoIndexConfig implements InitializingBean {

    public static final String SEAT_KEY_INDEX = "uniq_active_seat";

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        mongoTemplate.indexOps(Ticket.class).createIndex(new Index()
                .on("seatKey", Sort.Direction.ASC)
                .unique()
                .named(SEAT_KEY_INDEX)
                .partial(PartialIndexFilter.of(Criteria.where("seatKey").exists(true))));
    }
}
