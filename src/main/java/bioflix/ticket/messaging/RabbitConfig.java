package bioflix.ticket.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * All services share one topic exchange. ticket-service owns two queues; messages that still fail
 * after the listener retries go to a dead-letter queue so they can be inspected instead of looping.
 */
@Configuration
public class RabbitConfig {

    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String TICKET_ISSUED = "ticket.issued";

    public static final String CONFIRMED_QUEUE = "ticket-service.booking.confirmed";
    public static final String CANCELLED_QUEUE = "ticket-service.booking.cancelled";
    public static final String DEAD_LETTER_QUEUE = "ticket-service.dead-letter";

    @Bean
    TopicExchange eventsExchange(@Value("${app.rabbitmq.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Declarables ticketQueues(TopicExchange eventsExchange,
                             @Value("${app.rabbitmq.dead-letter-exchange}") String deadLetterExchange) {
        FanoutExchange dlx = new FanoutExchange(deadLetterExchange, true, false);
        Queue dlq = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        Queue confirmed = QueueBuilder.durable(CONFIRMED_QUEUE).deadLetterExchange(deadLetterExchange).build();
        Queue cancelled = QueueBuilder.durable(CANCELLED_QUEUE).deadLetterExchange(deadLetterExchange).build();

        Binding confirmedBinding = BindingBuilder.bind(confirmed).to(eventsExchange).with(BOOKING_CONFIRMED);
        Binding cancelledBinding = BindingBuilder.bind(cancelled).to(eventsExchange).with(BOOKING_CANCELLED);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx);

        return new Declarables(dlx, dlq, confirmed, cancelled, confirmedBinding, cancelledBinding, dlqBinding);
    }

    /** JSON on the wire. Picked up by both RabbitTemplate and the @RabbitListener containers. */
    @Bean
    MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
