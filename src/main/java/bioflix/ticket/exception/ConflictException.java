package bioflix.ticket.exception;

/** Mapped to HTTP 409 by GlobalExceptionHandler (business rule violated). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
