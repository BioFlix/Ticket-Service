package bioflix.ticket.service;

import bioflix.ticket.exception.InvalidQrTokenException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class QrTokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0000";
    private final QrTokenService tokens = new QrTokenService(SECRET, JsonMapper.builder().build());

    @Test
    void signedTokenVerifiesAndCarriesClaims() {
        // Arrange
        String token = tokens.sign("TK-AAAA-BBBB", "66f000000000000000000c01");

        // Act
        QrTokenService.Claims claims = tokens.verify(token);

        // Assert
        assertThat(token).startsWith("eyJhbGciOiJIUzI1NiJ9.");
        assertThat(claims).isEqualTo(new QrTokenService.Claims("TK-AAAA-BBBB", "66f000000000000000000c01"));
    }

    @Test
    void tamperedPayloadIsRejected() {
        // Arrange
        String[] parts = tokens.sign("TK-AAAA-BBBB", "show-1").split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"ticketNumber\":\"TK-ZZZZ-ZZZZ\",\"showtimeId\":\"show-1\"}".getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        // Act
        Throwable thrown = catchThrowable(() -> tokens.verify(tampered));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        // Arrange
        QrTokenService other = new QrTokenService("another-secret-another-secret-111111", JsonMapper.builder().build());
        String token = other.sign("TK-AAAA-BBBB", "show-1");

        // Act
        Throwable thrown = catchThrowable(() -> tokens.verify(token));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void plainTicketNumberIsRejected() {
        // Arrange
        String notAToken = "TK-AAAA-BBBB";

        // Act
        Throwable thrown = catchThrowable(() -> tokens.verify(notAToken));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        // Arrange
        String malformed = "a.b.c";

        // Act
        Throwable thrown = catchThrowable(() -> tokens.verify(malformed));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void missingTokenIsRejected() {
        // Arrange
        String missing = null;

        // Act
        Throwable thrown = catchThrowable(() -> tokens.verify(missing));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void shortSecretIsRefused() {
        // Arrange
        String shortSecret = "too-short";

        // Act
        Throwable thrown = catchThrowable(() -> new QrTokenService(shortSecret, JsonMapper.builder().build()));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ticketNumbersHaveTheDesignFormat() {
        // Arrange
        TicketNumberGenerator generator = new TicketNumberGenerator();

        // Act
        String ticketNumber = generator.next();

        // Assert
        assertThat(ticketNumber).matches("TK-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}");
    }
}
