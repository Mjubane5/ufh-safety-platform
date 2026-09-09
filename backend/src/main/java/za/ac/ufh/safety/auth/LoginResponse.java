package za.ac.ufh.safety.auth;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, UserSummary user) {}
