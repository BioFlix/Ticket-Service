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

    private TicketStatus status = TicketStatus.VALID;

    /** Text encoded in the QR code – scanned at the door. */
    private String qrPayload;

    /** QR code as PNG bytes. Later: move to object storage (S3) – "not yet wired" in the diagram. */
    private byte[] qrCodePng;

    private Instant issuedAt;
    private Instant usedAt;

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
        this.issuedAt = Instant.now();
    }

    public void markUsed() {
        this.status = TicketStatus.USED;
        this.usedAt = Instant.now();
    }

    public void cancel() {
        this.status = TicketStatus.CANCELLED;
    }

    public String getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public Long getUserId() { return userId; }
    public String getShowtimeId() { return showtimeId; }
    public String getMovieTitle() { return movieTitle; }
    public String getHallName() { return hallName; }
    public Instant getStartTime() { return startTime; }
    public String getSeat() { return seat; }
    public TicketStatus getStatus() { return status; }
    public String getQrPayload() { return qrPayload; }
    public void setQrPayload(String qrPayload) { this.qrPayload = qrPayload; }
    public byte[] getQrCodePng() { return qrCodePng; }
    public void setQrCodePng(byte[] qrCodePng) { this.qrCodePng = qrCodePng; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getUsedAt() { return usedAt; }
}
