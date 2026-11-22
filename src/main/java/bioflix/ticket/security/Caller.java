package bioflix.ticket.security;

/**
 * Who is calling, from the headers the API gateway sets after validating the token:
 * {@code X-User-Id} (the User service's UUID) and {@code X-User-Role} (CUSTOMER or ADMIN).
 */
public record Caller(String userId, String role) {

    public boolean isAdmin() {
        return role != null && role.trim().equalsIgnoreCase("ADMIN");
    }

    public boolean canSee(String ownerId) {
        return isAdmin() || (userId != null && userId.equals(ownerId));
    }
}
