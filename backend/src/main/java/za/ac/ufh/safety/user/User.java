package za.ac.ufh.safety.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
           @UniqueConstraint(name = "uk_users_student_number", columnNames = "student_number")
       })
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    /**
     * Null for staff accounts. Responders, campus control, GBV officers and
     * admins are not students and have no student number, so requiring one
     * would force us to invent fake numbers for real staff rows.
     *
     * Still unique when present, and still required when a student registers
     * through /api/auth/register — that check lives in RegisterRequest, which
     * only ever creates the student role.
     */
    @Column(name = "student_number", length = 20)
    private String studentNumber;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 30)
    private String phone;

    @Column(nullable = false, length = 30)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public String getStudentNumber() { return studentNumber; }
    public void setStudentNumber(String studentNumber) { this.studentNumber = studentNumber; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
}
