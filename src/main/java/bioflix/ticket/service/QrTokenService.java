package bioflix.ticket.service;

import bioflix.ticket.exception.InvalidQrTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The token in the QR code: a compact JWT (HS256) over { ticketNumber, showtimeId }.
 * Only someone with the secret can make a valid one, so QR codes can't be forged from a guessed ticket number.
 */
@Component
public class QrTokenService {

    private static final String HEADER = base64Url("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));

    public record Claims(String ticketNumber, String showtimeId) {
    }

    private final byte[] secret;
    private final JsonMapper jsonMapper;

    public QrTokenService(@Value("${app.ticket.qr-secret}") String secret, JsonMapper jsonMapper) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.ticket.qr-secret must be at least 32 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    public String sign(String ticketNumber, String showtimeId) {
        String payload = base64Url(jsonMapper.writeValueAsBytes(new Claims(ticketNumber, showtimeId)));
        String unsigned = HEADER + "." + payload;
        return unsigned + "." + base64Url(hmac(unsigned));
    }

    /** @throws InvalidQrTokenException if the token is malformed or the signature doesn't match */
    public Claims verify(String token) {
        String[] parts = token == null ? new String[0] : token.trim().split("\\.");
        if (parts.length != 3 || !HEADER.equals(parts[0])) {
            throw new InvalidQrTokenException();
        }
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQrTokenException();
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidQrTokenException();
        }
        try {
            Claims claims = jsonMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Claims.class);
            if (claims.ticketNumber() == null || claims.showtimeId() == null) {
                throw new InvalidQrTokenException();
            }
            return claims;
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new InvalidQrTokenException();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 not available", ex);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
