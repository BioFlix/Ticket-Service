package bioflix.ticket.messaging;

import bioflix.ticket.dto.IssueTicketsRequest;
import bioflix.ticket.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Exceptions thrown here are retried (see spring.rabbitmq.listener.simple.retry) and then
 * dead-lettered. Both handlers are idempotent, so a redelivered message is harmless.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final TicketService ticketService;

    public BookingEventListener(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @RabbitListener(queues = RabbitConfig.CONFIRMED_QUEUE)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        if (event.bookingId() == null || event.userId() == null
                || event.showtimeId() == null || event.showtimeId().isBlank() || event.seats() == null || event.seats().isEmpty()) {
            throw new AmqpRejectAndDontRequeueException("Invalid booking.confirmed event: " + event);
        }
        log.info("booking.confirmed received for booking {} ({} seats)", event.bookingId(), event.seats().size());
        ticketService.issueTickets(new IssueTicketsRequest(event.bookingId(), event.userId(), event.showtimeId(),
                event.movieTitle(), event.hallName(), event.startTime(), event.seats()));
    }

    @RabbitListener(queues = RabbitConfig.CANCELLED_QUEUE)
    public void onBookingCancelled(BookingCancelledEvent event) {
        if (event.bookingId() == null) {
            throw new AmqpRejectAndDontRequeueException("Invalid booking.cancelled event: " + event);
        }
        log.info("booking.cancelled received for booking {}", event.bookingId());
        ticketService.cancelTickets(event.bookingId());
    }
}
