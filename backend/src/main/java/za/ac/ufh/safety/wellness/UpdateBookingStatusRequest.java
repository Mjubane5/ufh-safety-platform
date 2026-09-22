package za.ac.ufh.safety.wellness;

import jakarta.validation.constraints.NotNull;

public record UpdateBookingStatusRequest(

        @NotNull(message = "status is required.")
        WellnessBookingStatus status
) {
}
