package za.ac.ufh.safety.gbv;

import jakarta.validation.constraints.NotNull;

public record UpdateGbvStatusRequest(

        @NotNull(message = "status is required.")
        GbvReportStatus status
) {
}
