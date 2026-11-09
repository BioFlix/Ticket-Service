package bioflix.ticket.entity;

public enum TicketStatus {
    VALID,      // can be scanned at the door
    USED,       // already scanned
    CANCELLED   // booking was cancelled
}
