package bioflix.ticket.integration;

import bioflix.ticket.entity.Ticket;
import bioflix.ticket.entity.TicketSeat;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.messaging.EventTypes;
import bioflix.ticket.messaging.RabbitConfig;
import bioflix.ticket.repository.TicketRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Integration test (run by {@code mvn verify}, not {@code mvn test}): real MongoDB + RabbitMQ in Docker.
 * Events are sent as raw JSON, exactly as another service would send them, so this also checks the event contract.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-attempts=2",
        "app.outbox.poll-interval=200ms"
})
class TicketFlowIT {

    private static final String ISSUED_TEST_QUEUE = "test.ticket.issued";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SHOW = "66f000000000000000000c01";
    private static final String ALICE = "11111111-1111-4111-8111-111111111111";

    @Container @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8");

    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange eventsExchange;
    @Autowired TicketRepository ticketRepository;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        for (String c : List.of("tickets", "processed_events", "outbox_events", "cancelled_bookings")) {
            mongoTemplate.getCollection(c).deleteMany(new Document());
        }
        Queue issued = new Queue(ISSUED_TEST_QUEUE, false, false, false);
        amqpAdmin.declareQueue(issued);
        amqpAdmin.declareBinding(BindingBuilder.bind(issued).to(eventsExchange).with(EventTypes.TICKET_ISSUED));
        amqpAdmin.purgeQueue(ISSUED_TEST_QUEUE, false);
        amqpAdmin.purgeQueue(RabbitConfig.DEAD_LETTER_QUEUE, false);
    }

    // ---------------------------------------------------------------- helpers

    private void send(String type, String eventId, String bookingId, String dataJson) {
        String json = """
                {"eventId":"%s","type":"%s","version":1,"occurredAt":"2026-10-07T18:42:10Z",
                 "producer":"booking-service","correlationId":"%s","data":%s}
                """.formatted(eventId, type, bookingId, dataJson);
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbitTemplate.send(eventsExchange.getName(), type, message);
    }

    private static String confirmedData(String bookingId, String... seats) {
        return """
                {"bookingId":"%s","userId":"%s","showtimeId":"%s","movieTitle":"Dune: Part Two",
                 "hallName":"Sal 3","startsAt":"2026-12-01T17:30:00Z","seats":[%s]}
                """.formatted(bookingId, ALICE, SHOW, String.join(",", seats));
    }

    private void confirm(String bookingId, String... seats) {
        send(EventTypes.BOOKING_CONFIRMED, UUID.randomUUID().toString(), bookingId, confirmedData(bookingId, seats));
    }

    private void cancel(String bookingId) {
        send(EventTypes.BOOKING_CANCELLED, UUID.randomUUID().toString(), bookingId, """
                {"bookingId":"%s","userId":"%s","reason":"USER","wasPaid":true}
                """.formatted(bookingId, ALICE));
    }

    private static String seat(String row, int number) {
        return "{\"row\":\"%s\",\"number\":%d,\"type\":\"STANDARD\",\"price\":13500}".formatted(row, number);
    }

    private List<Ticket> tickets(String bookingId) {
        return ticketRepository.findByBookingId(bookingId);
    }

    private JsonNode receiveIssued() {
        Message m = rabbitTemplate.receive(ISSUED_TEST_QUEUE, WAIT.toMillis());
        assertThat(m).as("ticket.issued message").isNotNull();
        return jsonMapper.readTree(m.getBody());
    }

    private long count(String collection) {
        return mongoTemplate.getCollection(collection).countDocuments();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void confirmIssuesTicketsAndPublishesTicketIssuedThroughTheOutbox() {
        // Arrange
        String bookingId = "b-1";

        // Act
        confirm(bookingId, seat("F", 7), seat("F", 8));

        // Assert
        await().atMost(WAIT).until(() -> tickets(bookingId).size() == 2);
        Ticket ticket = tickets(bookingId).getFirst();
        assertThat(ticket.getTicketNumber()).matches("TK-....-....");
        assertThat(ticket.getSeat().row()).isEqualTo("F");
        assertThat(ticket.getStartsAt()).isEqualTo(Instant.parse("2026-12-01T17:30:00Z"));

        JsonNode envelope = receiveIssued();
        assertThat(envelope.get("type").asString()).isEqualTo("ticket.issued");
        assertThat(envelope.get("producer").asString()).isEqualTo("ticket-service");
        assertThat(envelope.get("correlationId").asString()).isEqualTo(bookingId);
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isNotNull();
        JsonNode data = envelope.get("data");
        assertThat(data.get("bookingId").asString()).isEqualTo(bookingId);
        assertThat(data.get("userId").asString()).isEqualTo(ALICE);
        assertThat(data.get("tickets")).hasSize(2);
        assertThat(data.get("tickets").get(0).get("qrToken").asString()).startsWith("eyJ");

        await().atMost(WAIT).until(() -> mongoTemplate.getCollection("outbox_events")
                .countDocuments(new Document("publishedAt", new Document("$ne", null))) == 1);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    @Test
    void sameEventTwiceIsHandledOnceAndOnlyOneTicketIssuedIsSent() {
        // Arrange
        String eventId = UUID.randomUUID().toString();
        String data = confirmedData("b-2", seat("A", 1));

        // Act
        send(EventTypes.BOOKING_CONFIRMED, eventId, "b-2", data);
        send(EventTypes.BOOKING_CONFIRMED, eventId, "b-2", data);
        confirm("b-2", seat("A", 1));   // different eventId, same booking

        // Assert
        await().atMost(WAIT).until(() -> count("processed_events") == 2);
        assertThat(tickets("b-2")).hasSize(1);
        receiveIssued();
        assertThat(rabbitTemplate.receive(ISSUED_TEST_QUEUE, 1000)).as("only one ticket.issued per booking").isNull();
    }

    @Test
    void cancelVoidsTickets() {
        // Arrange
        confirm("b-3", seat("F", 5));
        await().atMost(WAIT).until(() -> tickets("b-3").size() == 1);

        // Act
        cancel("b-3");

        // Assert
        await().atMost(WAIT).until(() -> tickets("b-3").getFirst().getStatus() == TicketStatus.VOID);
        assertThat(tickets("b-3").getFirst().getVoidedAt()).isNotNull();
    }

    @Test
    void cancelledSeatCanBeTicketedForANewBooking() {
        // Arrange
        confirm("b-3", seat("F", 5));
        await().atMost(WAIT).until(() -> tickets("b-3").size() == 1);
        cancel("b-3");
        await().atMost(WAIT).until(() -> tickets("b-3").getFirst().getStatus() == TicketStatus.VOID);

        // Act
        confirm("b-4", seat("F", 5));

        // Assert
        await().atMost(WAIT).until(() -> tickets("b-4").size() == 1);
        assertThat(tickets("b-4").getFirst().getStatus()).isEqualTo(TicketStatus.VALID);
    }

    @Test
    void lateConfirmForCancelledBookingIssuesNothing() {
        // Arrange
        cancel("b-5");
        await().atMost(WAIT).until(() -> count("cancelled_bookings") == 1);

        // Act
        confirm("b-5", seat("D", 1));
        confirm("b-6", seat("D", 2));   // processed after b-5 on the same queue

        // Assert
        await().atMost(WAIT).until(() -> tickets("b-6").size() == 1);
        assertThat(tickets("b-5")).isEmpty();
    }

    @Test
    void eventWithoutEnvelopeIsDeadLettered() {
        // Arrange
        Message bare = MessageBuilder.withBody("{\"bookingId\":\"b-7\",\"seats\":[\"C7\"]}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();

        // Act
        rabbitTemplate.send(eventsExchange.getName(), EventTypes.BOOKING_CONFIRMED, bare);

        // Assert
        await().atMost(WAIT).until(() -> rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE) != null);
        assertThat(tickets("b-7")).isEmpty();
    }

    @Test
    void uniqueIndexStopsTwoTicketsForTheSameBookedSeat() {
        // Arrange
        ticketRepository.insert(new Ticket("TK-AAAA-AAAA", "b-8", ALICE, SHOW,
                new TicketSeat("A", 1, "STANDARD"), "Dune", "Sal 1", Instant.now(), "tok1"));
        Ticket sameSeat = new Ticket("TK-BBBB-BBBB", "b-8", ALICE, SHOW,
                new TicketSeat("A", 1, "STANDARD"), "Dune", "Sal 1", Instant.now(), "tok2");

        // Act
        Throwable thrown = catchThrowable(() -> ticketRepository.insert(sameSeat));

        // Assert
        assertThat(thrown).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void uniqueIndexStopsDuplicateTicketNumbers() {
        // Arrange
        ticketRepository.insert(new Ticket("TK-AAAA-AAAA", "b-8", ALICE, SHOW,
                new TicketSeat("A", 1, "STANDARD"), "Dune", "Sal 1", Instant.now(), "tok1"));
        Ticket sameNumber = new Ticket("TK-AAAA-AAAA", "b-9", ALICE, SHOW,
                new TicketSeat("A", 2, "STANDARD"), "Dune", "Sal 1", Instant.now(), "tok3");

        // Act
        Throwable thrown = catchThrowable(() -> ticketRepository.insert(sameNumber));

        // Assert
        assertThat(thrown).isInstanceOf(DuplicateKeyException.class);
    }
}
