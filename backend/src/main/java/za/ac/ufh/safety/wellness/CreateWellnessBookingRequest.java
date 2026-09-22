package za.ac.ufh.safety.wellness;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateWellnessBookingRequest(

        @NotNull(message = "Choose a resource.")
        Long resourceId,

        @NotNull(message = "Choose a preferred date.")
        LocalDate preferredDate,

        @NotBlank(message = "Choose a preferred slot.")
        String preferredSlot,

        @Size(max = 500, message = "Note is too long.")
        String note
) {
}
