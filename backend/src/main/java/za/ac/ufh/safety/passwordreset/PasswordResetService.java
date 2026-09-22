package za.ac.ufh.safety.passwordreset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.auth.EmailService;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

/**
 * POST /api/auth/forgot-password and POST /api/auth/reset-password.
 *
 * Deliberately never reveals whether an email is registered (same generic
 * response either way - see requestReset) and never distinguishes an
 * expired token from an already-used or unknown one (same generic 400 -
 * see resetPassword). Both are the exact enumeration/timing protections
 * docs/api-contract.md calls for; weakening either one to give a more
 * helpful error message would be the bug, not a missing feature.
 */
@Service
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final int TOKEN_BYTES = 32; // 256 bits - see PasswordResetToken's class comment

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(
        PasswordResetTokenRepository tokens,
        UserRepository users,
        PasswordEncoder passwordEncoder,
        EmailService emailService
    ) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    @Transactional
    public MessageResponse requestReset(ForgotPasswordRequest request, String frontendBaseUrl) {
        users.findByEmailIgnoreCase(request.email()).ifPresent(user -> {
            String rawToken = generateRawToken();

            PasswordResetToken token = new PasswordResetToken();
            token.setUserId(user.getUserId());
            token.setTokenHash(sha256Hex(rawToken));
            token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
            tokens.save(token);

            String resetUrl = frontendBaseUrl + "/reset-password.html?token=" + rawToken;
            emailService.sendPasswordResetLink(user.getEmail(), resetUrl);
        });

        // Same message whether or not the email matched a user - see the
        // class comment.
        return new MessageResponse("If that email is registered, we've sent a reset link.");
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        PasswordResetToken token = tokens.findByTokenHash(sha256Hex(request.token()))
            .orElseThrow(this::invalidToken);

        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw invalidToken();
        }

        User user = users.findById(token.getUserId()).orElseThrow(this::invalidToken);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.save(user);

        token.setUsedAt(Instant.now());
        tokens.save(token);

        return new MessageResponse("Password updated. Sign in with your new password.");
    }

    private ApiException invalidToken() {
        // One generic message for wrong/expired/already-used, on purpose -
        // see the class comment.
        return new ApiException(400, "INVALID_TOKEN", "This reset link is invalid or has expired.", null);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm (JLS/JCA baseline) - this
            // can only happen if the JVM itself is broken.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
