package za.ac.ufh.safety.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyLoginCodeRequest(
    @NotBlank(message = "pendingLoginId is required.")
    String pendingLoginId,

    @NotBlank(message = "code is required.")
    @Pattern(regexp = "\\d{6}", message = "code must be 6 digits.")
    String code
) {}
