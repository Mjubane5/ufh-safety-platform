package za.ac.ufh.safety.wellness;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(

        @NotBlank(message = "Message cannot be empty.")
        @Size(max = 500, message = "Message is too long.")
        String text
) {
}
