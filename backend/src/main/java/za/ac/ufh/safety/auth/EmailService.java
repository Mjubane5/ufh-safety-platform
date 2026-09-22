package za.ac.ufh.safety.auth;

/**
 * The emails this application sends. Kept as an interface rather than
 * calling a provider directly from the services that need it, so the
 * provider can be swapped (or mocked in tests) without touching login or
 * password-reset logic.
 */
public interface EmailService {
    void sendLoginCode(String toEmail, String code);

    /** resetUrl already has ?token=... on it - this method never sees the raw token, just where to send someone. */
    void sendPasswordResetLink(String toEmail, String resetUrl);
}
