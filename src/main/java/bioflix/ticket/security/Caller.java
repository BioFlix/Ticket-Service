package bioflix.ticket.security;

import java.util.Locale;
import java.util.Set;

/**
 * Who is calling, from the headers the API gateway sets after validating the token:
 * {@code X-User-Id} and {@code X-User-Role}.
 */
public record Caller(Long userId, String role) {

    private static final Set<String> STAFF_ROLES = Set.of("STAFF", "ADMIN");

    public boolean isStaff() {
        return role != null && STAFF_ROLES.contains(role.trim().toUpperCase(Locale.ROOT));
    }

    public boolean canSee(Long ownerId) {
        return isStaff() || (userId != null && userId.equals(ownerId));
    }
}
