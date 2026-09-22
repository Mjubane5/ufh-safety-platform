package za.ac.ufh.safety.passwordreset;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Enter a valid email address.")
        String email
) {
}
