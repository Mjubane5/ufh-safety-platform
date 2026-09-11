package za.ac.ufh.safety.responders;

import java.time.Instant;
import za.ac.ufh.safety.user.User;

/** One row of GET /api/responders/available. Contract section 4. */
public record ResponderSummary(
    Long responderId,
    String fullName,
    String team,
    ResponderStatus status,
    Double latitude,
    Double longitude,
    Instant lastSeenAt
) {
    /**
     * `user` may be null if the duty row outlived the account it points at.
     * The name is then null rather than the call failing — a dispatcher
     * seeing one nameless row is better than an empty responder list.
     */
    static ResponderSummary of(Responder responder, User user) {
        return new ResponderSummary(
            responder.getResponderId(),
            user == null ? null : user.getFullName(),
            responder.getTeam(),
            responder.getStatus(),
            responder.getLatitude(),
            responder.getLongitude(),
            responder.getLastSeenAt());
    }
}
