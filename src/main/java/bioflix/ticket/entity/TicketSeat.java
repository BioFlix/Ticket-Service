package bioflix.ticket.entity;

/** The seat, embedded in the ticket. type is STANDARD, VIP or WHEELCHAIR. */
public record TicketSeat(String row, int number, String type) {

    /** "F7" – for display and logs. */
    public String label() {
        return row + number;
    }
}
