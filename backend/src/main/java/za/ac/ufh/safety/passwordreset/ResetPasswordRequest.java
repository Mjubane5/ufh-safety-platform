package za.ac.ufh.safety.passwordreset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @NotBlank(message = "token is required.")
        String token,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, message = "Password must be at least 8 characters.")
        @Size(max = 72, message = "Password must be 72 characters or fewer.")
        String newPassword
) {
}
