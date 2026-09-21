package za.ac.ufh.safety.auth;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One pending two-step login for a student - see AuthService's
 * requestLoginCode/resendLoginCode/verifyLoginCode. codeHash is a BCrypt
 * hash of the 6-digit code (the same PasswordEncoder bean used for account
 * passwords), never the code itself - a leaked row must not hand out a
 * usable code any more than a leaked users table hands out a password.
 */
@Entity
@Table(name = "login_verification_codes")
public class LoginVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long verificationId;

    @Column(name = "pending_login_id", nullable = false, unique = true, length = 64)
    private String pendingLoginId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LoginVerificationCode() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getVerificationId() {
        return verificationId;
    }

    public String getPendingLoginId() {
        return pendingLoginId;
    }

    public void setPendingLoginId(String pendingLoginId) {
        this.pendingLoginId = pendingLoginId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Instant lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
