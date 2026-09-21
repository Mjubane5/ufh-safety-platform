package za.ac.ufh.safety.auth;

/**
 * The only email this application sends right now. Kept as an interface
 * rather than calling a provider directly from AuthService so the provider
 * can be swapped (or mocked in tests) without touching the login logic.
 */
public interface EmailService {
    void sendLoginCode(String toEmail, String code);
}
