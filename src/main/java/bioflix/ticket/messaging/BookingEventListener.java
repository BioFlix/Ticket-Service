package bioflix.ticket.messaging;

import bioflix.ticket.inbox.ProcessedEventService;
import bioflix.ticket.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Each event is handled once per eventId (processed_events). Exceptions are retried
 * (spring.rabbitmq.listener.simple.retry) and then dead-lettered.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final TicketService ticketService;
    private final ProcessedEventService processedEvents;

    public BookingEventListener(TicketService ticketService, ProcessedEventService processedEvents) {
        this.ticketService = ticketService;
        this.processedEvents = processedEvents;
    }

    @RabbitListener(queues = RabbitConfig.CONFIRMED_QUEUE)
    public void onBookingConfirmed(EventEnvelope<BookingConfirmed> event) {
        BookingConfirmed data = event.data();
        if (event.eventId() == null || data == null || isBlank(data.bookingId()) || isBlank(data.userId())
                || isBlank(data.showtimeId()) || data.seats() == null || data.seats().isEmpty()
                || data.seats().stream().anyMatch(s -> s == null || isBlank(s.row()) || s.number() == null)) {
            throw new AmqpRejectAndDontRequeueException("Invalid booking.confirmed event: " + event);
        }
        processedEvents.runOnce(event, () -> {
            log.info("booking.confirmed {} for booking {} ({} seats)", event.eventId(), data.bookingId(), data.seats().size());
            ticketService.issueTickets(data, event.correlationId());
        });
    }

    @RabbitListener(queues = RabbitConfig.CANCELLED_QUEUE)
    public void onBookingCancelled(EventEnvelope<BookingCancelled> event) {
        BookingCancelled data = event.data();
        if (event.eventId() == null || data == null || isBlank(data.bookingId())) {
            throw new AmqpRejectAndDontRequeueException("Invalid booking.cancelled event: " + event);
        }
        processedEvents.runOnce(event, () -> {
            log.info("booking.cancelled {} for booking {} ({})", event.eventId(), data.bookingId(), data.reason());
            ticketService.voidTickets(data.bookingId(), data.reason());
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
