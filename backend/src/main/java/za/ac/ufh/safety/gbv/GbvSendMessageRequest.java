package za.ac.ufh.safety.gbv;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GbvSendMessageRequest(

        @NotBlank(message = "Message cannot be empty.")
        @Size(max = 500, message = "Message is too long.")
        String text
) {
}
