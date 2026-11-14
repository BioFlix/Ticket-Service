package bioflix.ticket.messaging;

import bioflix.ticket.entity.Ticket;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TicketEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public TicketEventPublisher(RabbitTemplate rabbitTemplate, @Value("${app.rabbitmq.exchange}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    /** All tickets belong to the same booking. */
    public void publishTicketsIssued(List<Ticket> tickets) {
        Ticket first = tickets.getFirst();
        List<TicketIssuedEvent.IssuedTicket> issued = tickets.stream()
                .map(t -> new TicketIssuedEvent.IssuedTicket(t.getId(), t.getSeat(), Ticket.qrUrl(t.getId())))
                .toList();
        TicketIssuedEvent event = new TicketIssuedEvent(first.getBookingId(), first.getUserId(), first.getShowtimeId(),
                first.getMovieTitle(), first.getHallName(), first.getStartTime(), issued);
        rabbitTemplate.convertAndSend(exchange, RabbitConfig.TICKET_ISSUED, event);
    }
}
