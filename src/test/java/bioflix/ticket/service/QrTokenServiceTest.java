package bioflix.ticket.service;

import bioflix.ticket.exception.InvalidQrTokenException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QrTokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0000";
    private final QrTokenService tokens = new QrTokenService(SECRET, JsonMapper.builder().build());

    @Test
    void signedTokenVerifiesAndCarriesClaims() {
        String token = tokens.sign("TK-AAAA-BBBB", "66f000000000000000000c01");

        assertThat(token).startsWith("eyJhbGciOiJIUzI1NiJ9").contains(".");
        assertThat(tokens.verify(token))
                .isEqualTo(new QrTokenService.Claims("TK-AAAA-BBBB", "66f000000000000000000c01"));
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = tokens.sign("TK-AAAA-BBBB", "show-1").split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"ticketNumber\":\"TK-ZZZZ-ZZZZ\",\"showtimeId\":\"show-1\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tokens.verify(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        QrTokenService other = new QrTokenService("another-secret-another-secret-111111", JsonMapper.builder().build());
        String token = other.sign("TK-AAAA-BBBB", "show-1");

        assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> tokens.verify("TK-AAAA-BBBB")).isInstanceOf(InvalidQrTokenException.class);
        assertThatThrownBy(() -> tokens.verify("a.b.c")).isInstanceOf(InvalidQrTokenException.class);
        assertThatThrownBy(() -> tokens.verify(null)).isInstanceOf(InvalidQrTokenException.class);
    }

    @Test
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new QrTokenService("too-short", JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ticketNumbersHaveTheDesignFormat() {
        assertThat(new TicketNumberGenerator().next()).matches("TK-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}");
    }
}
