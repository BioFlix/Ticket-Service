package bioflix.ticket.messaging;

public final class EventTypes {

    public static final String PRODUCER = "ticket-service";

    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String TICKET_ISSUED = "ticket.issued";

    private EventTypes() {
    }
}
