package za.ac.ufh.safety.incidents;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AppendSignalRequest(

        @NotNull(message = "type is required.")
        SignalType type,

        @Size(max = 50, message = "label is too long.")
        String label,

        Double confidence,

        @Size(max = 500, message = "text is too long.")
        String text
) {
}
