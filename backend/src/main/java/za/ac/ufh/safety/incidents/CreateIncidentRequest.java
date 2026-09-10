package za.ac.ufh.safety.incidents;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/incidents, contract section 3.
 *
 * Coordinates are deliberately optional. A student whose browser blocks
 * location, or who is indoors with no fix, must still be able to report.
 * Out-of-range coordinates are a different matter and are rejected here.
 *
 * Two rules that need more than one field are checked in IncidentService:
 * description is required when the type is "other", and a latitude without a
 * longitude is not a location.
 */
public record CreateIncidentRequest(

    @NotNull(message = "Incident type is required.")
    IncidentType type,

    @Size(max = 1000, message = "Description must be 1000 characters or fewer.")
    String description,

    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90.")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90.")
    Double latitude,

    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180.")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180.")
    Double longitude,

    @PositiveOrZero(message = "Accuracy cannot be negative.")
    Double accuracy,

    @NotNull(message = "Say whether the report is anonymous.")
    Boolean anonymous
) {}
