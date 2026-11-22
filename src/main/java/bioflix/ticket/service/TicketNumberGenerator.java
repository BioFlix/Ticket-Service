package bioflix.ticket.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Makes ticket numbers like TK-7F3K-92QD. No 0/O/1/I, so they're easy to read out at the door. */
@Component
public class TicketNumberGenerator {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder("TK-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
