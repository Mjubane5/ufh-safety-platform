package za.ac.ufh.safety.auth;

/** Deliberately never carries the code itself - see EmailService. */
public record PendingLoginResponse(String pendingLoginId, String maskedEmail) {}
