package za.ac.ufh.safety.incidents;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/incidents/{incidentId}/status.
 *
 * `note` is accepted because the contract defines it, but it is not stored
 * yet — that needs an incident_notes table. It is validated for length so the
 * field cannot be used to push a megabyte into the request.
 */
public record UpdateStatusRequest(
    @NotNull(message = "status is required.")
    IncidentStatus status,

    @Size(max = 500, message = "note cannot be longer than 500 characters.")
    String note
) {}
