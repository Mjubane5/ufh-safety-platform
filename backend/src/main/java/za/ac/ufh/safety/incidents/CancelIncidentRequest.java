package za.ac.ufh.safety.incidents;

import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/incidents/{incidentId}/cancel — the false-alarm path.
 *
 * `reason` is optional. A student who pressed SOS by accident should not be
 * made to write an explanation before the alarm stops.
 */
public record CancelIncidentRequest(
    @Size(max = 500, message = "reason cannot be longer than 500 characters.")
    String reason
) {}
