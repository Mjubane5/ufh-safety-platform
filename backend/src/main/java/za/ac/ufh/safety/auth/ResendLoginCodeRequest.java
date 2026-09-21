package za.ac.ufh.safety.auth;

import jakarta.validation.constraints.NotBlank;

public record ResendLoginCodeRequest(
    @NotBlank(message = "pendingLoginId is required.")
    String pendingLoginId
) {}
