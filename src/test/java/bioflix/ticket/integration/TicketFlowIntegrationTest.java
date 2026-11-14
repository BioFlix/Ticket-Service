package bioflix.ticket.integration;

import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.messaging.BookingCancelledEvent;
import bioflix.ticket.messaging.BookingConfirmedEvent;
import bioflix.ticket.messaging.RabbitConfig;
import bioflix.ticket.messaging.TicketIssuedEvent;
import bioflix.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Real MongoDB + RabbitMQ in Docker: events in, tickets in Mongo, ticket.issued out. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-attempts=3"
})
class TicketFlowIntegrationTest {

    private static final String ISSUED_TEST_QUEUE = "test.ticket.issued";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Container @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8");

    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange eventsExchange;
    @Autowired TicketRepository ticketRepository;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.getDb().drop();
        // re-create the partial unique index the app made at startup
        new bioflix.ticket.config.MongoIndexConfig(mongoTemplate).afterPropertiesSet();

        Queue issued = new Queue(ISSUED_TEST_QUEUE, false, false, false);
        amqpAdmin.declareQueue(issued);
        amqpAdmin.declareBinding(BindingBuilder.bind(issued).to(eventsExchange).with(RabbitConfig.TICKET_ISSUED));
        amqpAdmin.purgeQueue(ISSUED_TEST_QUEUE, false);
        amqpAdmin.purgeQueue(RabbitConfig.DEAD_LETTER_QUEUE, false);
    }

    private void confirm(long bookingId, long userId, String... seats) {
        rabbitTemplate.convertAndSend(eventsExchange.getName(), RabbitConfig.BOOKING_CONFIRMED,
                new BookingConfirmedEvent(bookingId, userId, "show-1", "Dune", "Sal 1",
                        Instant.parse("2026-12-01T19:00:00Z"), List.of(seats)));
    }

    private void cancel(long bookingId) {
        rabbitTemplate.convertAndSend(eventsExchange.getName(), RabbitConfig.BOOKING_CANCELLED,
                new BookingCancelledEvent(bookingId, "Cancelled by customer"));
    }

    private List<Ticket> tickets(long bookingId) {
        return ticketRepository.findByBookingId(bookingId);
    }

    @Test
    void confirmIssuesTicketsAndPublishesTicketIssued() {
        confirm(1, 10, "C7", "C8");

        await().atMost(WAIT).until(() -> tickets(1).size() == 2);
        assertThat(tickets(1)).allSatisfy(t -> assertThat(t.getQrCodePng()).isNotEmpty());

        TicketIssuedEvent event = rabbitTemplate.receiveAndConvert(ISSUED_TEST_QUEUE, WAIT.toMillis(),
                new ParameterizedTypeReference<TicketIssuedEvent>() { });
        assertThat(event).isNotNull();
        assertThat(event.bookingId()).isEqualTo(1L);
        assertThat(event.userId()).isEqualTo(10L);
        assertThat(event.tickets()).extracting(TicketIssuedEvent.IssuedTicket::seat)
                .containsExactlyInAnyOrder("C7", "C8");
    }

    @Test
    void cancelledSeatCanBeBookedAgain() {
        confirm(1, 10, "C7");
        await().atMost(WAIT).until(() -> tickets(1).size() == 1);

        cancel(1);
        await().atMost(WAIT).until(() -> tickets(1).getFirst().getStatus() == TicketStatus.CANCELLED);

        confirm(2, 20, "C7");
        await().atMost(WAIT).until(() -> tickets(2).size() == 1);
        assertThat(tickets(2).getFirst().getStatus()).isEqualTo(TicketStatus.VALID);
    }

    @Test
    void activeSeatIsNotTicketedTwiceAndMessageIsDeadLettered() {
        confirm(1, 10, "C7");
        await().atMost(WAIT).until(() -> tickets(1).size() == 1);

        confirm(2, 20, "C7");

        await().atMost(WAIT).until(() -> rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE) != null);
        assertThat(tickets(2)).isEmpty();
    }

    @Test
    void lateConfirmForCancelledBookingIssuesNothing() {
        cancel(3);
        await().atMost(WAIT).until(() -> mongoTemplate.getCollection("cancelled_bookings").countDocuments() == 1);

        confirm(3, 10, "D1");
        confirm(4, 10, "D2");   // processed after booking 3 on the same queue
        await().atMost(WAIT).until(() -> tickets(4).size() == 1);
        assertThat(tickets(3)).isEmpty();
    }

    @Test
    void uniqueIndexBlocksTwoActiveTicketsButIgnoresCancelled() {
        Ticket first = ticketRepository.save(new Ticket(1L, 10L, "show-9", "Dune", "Sal 1", Instant.now(), "A1"));
        assertThatThrownBy(() -> ticketRepository.save(new Ticket(2L, 20L, "show-9", "Dune", "Sal 1", Instant.now(), "A1")))
                .isInstanceOf(DuplicateKeyException.class);

        first.cancel();
        ticketRepository.save(first);
        ticketRepository.save(new Ticket(2L, 20L, "show-9", "Dune", "Sal 1", Instant.now(), "A1"));
        assertThat(tickets(2)).hasSize(1);
    }
}
