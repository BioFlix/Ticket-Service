package bioflix.ticket.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One ticket = one seat. */
@Document(collection = "tickets")
public class Ticket {

    @Id
    private String id;

    @Indexed
    private Long bookingId;

    @Indexed
    private Long userId;

    private String showtimeId;
    private String movieTitle;
    private String hallName;
    private Instant startTime;
    private String seat;

    /**
     * showtimeId|seat while the ticket holds the seat (VALID or USED). Removed on cancel so the
     * seat can be ticketed again for a new booking. Unique via a partial index (MongoIndexConfig).
     */
    private String seatKey;

    private TicketStatus status = TicketStatus.VALID;

    /** Text encoded in the QR code – scanned at the door. */
    private String qrPayload;

    /** QR code as PNG bytes. Later: move to object storage (S3) – "not yet wired" in the diagram. */
    private byte[] qrCodePng;

    private Instant issuedAt;
    private Instant usedAt;
    private Instant cancelledAt;

    public Ticket() {
    }

    public Ticket(Long bookingId, Long userId, String showtimeId, String movieTitle, String hallName,
                  Instant startTime, String seat) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.showtimeId = showtimeId;
        this.movieTitle = movieTitle;
        this.hallName = hallName;
        this.startTime = startTime;
        this.seat = seat;
        this.seatKey = seatKey(showtimeId, seat);
        this.issuedAt = Instant.now();
    }

    public static String seatKey(String showtimeId, String seat) {
        return showtimeId + "|" + seat;
    }

    public static String qrUrl(String ticketId) {
        return "/api/tickets/" + ticketId + "/qr";
    }

    public void markUsed() {
        this.status = TicketStatus.USED;
        this.usedAt = Instant.now();
    }

    /** Voids the ticket and releases the seat. */
    public void cancel() {
        this.status = TicketStatus.CANCELLED;
        this.seatKey = null;   // null fields aren't written, so the partial unique index ignores this ticket
        this.cancelledAt = Instant.now();
    }

    public String getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public Long getUserId() { return userId; }
    public String getShowtimeId() { return showtimeId; }
    public String getMovieTitle() { return movieTitle; }
    public String getHallName() { return hallName; }
    public Instant getStartTime() { return startTime; }
    public String getSeat() { return seat; }
    public String getSeatKey() { return seatKey; }
    public TicketStatus getStatus() { return status; }
    public String getQrPayload() { return qrPayload; }
    public void setQrPayload(String qrPayload) { this.qrPayload = qrPayload; }
    public byte[] getQrCodePng() { return qrCodePng; }
    public void setQrCodePng(byte[] qrCodePng) { this.qrCodePng = qrCodePng; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
