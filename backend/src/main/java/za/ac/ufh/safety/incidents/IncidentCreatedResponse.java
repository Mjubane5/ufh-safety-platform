package za.ac.ufh.safety.incidents;

import java.time.Instant;

/** 201 body for POST /api/incidents, contract section 3. */
public record IncidentCreatedResponse(
    Long incidentId,
    IncidentType type,
    IncidentStatus status,
    Integer priority,
    String locationSource,
    Instant createdAt
) {}
