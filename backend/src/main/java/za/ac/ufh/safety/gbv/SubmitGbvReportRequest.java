package za.ac.ufh.safety.gbv;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

// evidenceIds is deliberately not a field here: evidence upload is out of
// scope for this pass (see POST /api/gbv/evidence in the contract, not yet
// built). Jackson ignores unknown JSON properties by default, so a frontend
// that still sends it is not rejected - the value is just not stored yet.
public record SubmitGbvReportRequest(

        @NotNull(message = "anonymous is required.")
        Boolean anonymous,

        @Size(max = 2000, message = "Description is too long.")
        String description,

        Instant occurredAt,

        Double latitude,

        Double longitude,

        String contactPreference
) {
}
