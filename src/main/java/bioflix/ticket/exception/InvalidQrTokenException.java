package bioflix.ticket.exception;

/** Mapped to HTTP 400 – the scanned QR code isn't a genuine ticket. */
public class InvalidQrTokenException extends RuntimeException {

    public InvalidQrTokenException() {
        super("Invalid QR code");
    }
}
