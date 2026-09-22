package za.ac.ufh.safety.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * The "email" field also accepts a bare student number (e.g. "202512345") -
 * login.html has always advertised this, but nothing before AuthService
 * actually accepted one, so it silently failed. No @Email constraint here
 * on purpose: AuthService.verifyCredentials decides which lookup to use
 * based on the value's shape.
 */
public record LoginRequest(
    @NotBlank(message = "Enter your student number or email.")
    String email,

    @NotBlank(message = "Password is required.")
    String password
) {}
