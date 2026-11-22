package bioflix.ticket.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One ticket = one booked seat. Self-contained (snapshot of movie, hall and time), so it can be
 * scanned without calling other services.
 */
@Document(collection = "tickets")
@CompoundIndex(name = "one_ticket_per_booked_seat", def = "{'bookingId': 1, 'seat.row': 1, 'seat.number': 1}", unique = true)
@CompoundIndex(name = "my_tickets", def = "{'userId': 1, 'startsAt': -1}")
@CompoundIndex(name = "door_scanning", def = "{'showtimeId': 1, 'status': 1}")
public class Ticket {

    @Id
    private String id;

    /** Human-readable and unique, e.g. TK-7F3K-92QD. */
    @Indexed(unique = true)
    private String ticketNumber;

    private String bookingId;    // Booking UUID
    private String userId;       // User UUID
    private String showtimeId;   // Catalog ObjectId

    private TicketSeat seat;

    // Snapshot from booking.confirmed
    private String movieTitle;
    private String hallName;
    private Instant startsAt;

    /** HMAC-signed { ticketNumber, showtimeId } – this is what the QR code contains. */
    private String qrToken;

    private TicketStatus status = TicketStatus.VALID;

    private Instant issuedAt;
    private Instant scannedAt;
    private Instant voidedAt;

    public Ticket() {
    }

    public Ticket(String ticketNumber, String bookingId, String userId, String showtimeId, TicketSeat seat,
                  String movieTitle, String hallName, Instant startsAt, String qrToken) {
        this.ticketNumber = ticketNumber;
        this.bookingId = bookingId;
        this.userId = userId;
        this.showtimeId = showtimeId;
        this.seat = seat;
        this.movieTitle = movieTitle;
        this.hallName = hallName;
        this.startsAt = startsAt;
        this.qrToken = qrToken;
        this.issuedAt = Instant.now();
    }

    public static String qrUrl(String ticketId) {
        return "/api/tickets/" + ticketId + "/qr";
    }

    public void markScanned() {
        this.status = TicketStatus.SCANNED;
        this.scannedAt = Instant.now();
    }

    public void markVoid() {
        this.status = TicketStatus.VOID;
        this.voidedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTicketNumber() { return ticketNumber; }
    public String getBookingId() { return bookingId; }
    public String getUserId() { return userId; }
    public String getShowtimeId() { return showtimeId; }
    public TicketSeat getSeat() { return seat; }
    public String getMovieTitle() { return movieTitle; }
    public String getHallName() { return hallName; }
    public Instant getStartsAt() { return startsAt; }
    public String getQrToken() { return qrToken; }
    public TicketStatus getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getScannedAt() { return scannedAt; }
    public Instant getVoidedAt() { return voidedAt; }
}
