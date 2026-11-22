package bioflix.ticket.entity;

/** Ticket lifecycle: VALID -> SCANNED (at the door) or VALID -> VOID (booking cancelled). */
public enum TicketStatus {
    VALID,
    SCANNED,
    VOID
}
