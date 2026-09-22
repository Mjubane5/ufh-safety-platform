package za.ac.ufh.safety.healthprofile;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateHealthProfileRequest(

        @NotNull(message = "conditions is required (an empty list is fine).")
        List<String> conditions,

        @Size(max = 200, message = "Note must be 200 characters or fewer.")
        String note
) {
}
