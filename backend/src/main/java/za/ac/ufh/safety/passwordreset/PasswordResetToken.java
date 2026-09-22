package za.ac.ufh.safety.passwordreset;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A single-use, short-lived link token for POST /api/auth/reset-password.
 *
 * Stores a SHA-256 digest of the token, not the token itself - unlike the
 * two-step login code (LoginVerificationCode, hashed with BCrypt and looked
 * up by a separate pendingLoginId), this token IS its own lookup key, so it
 * needs a fast, deterministic hash for a direct findByTokenHash query.
 * BCrypt is deliberately for low-entropy secrets (a 6-digit code, a human
 * password) where a slow hash resists brute-forcing a small keyspace; this
 * token is 256 bits of SecureRandom, whose keyspace is already astronomical,
 * so a fast hash is both correct and necessary here.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long tokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getTokenId() { return tokenId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
