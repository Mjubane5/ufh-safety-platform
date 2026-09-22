package za.ac.ufh.safety.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    // Two-step login (see requestLoginCode/resendLoginCode/verifyLoginCode).
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginVerificationCodeRepository loginCodes;
    private final EmailService emailService;

    public AuthService(
        UserRepository users,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        LoginVerificationCodeRepository loginCodes,
        EmailService emailService
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginCodes = loginCodes;
        this.emailService = emailService;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String studentNumber = request.studentNumber().trim();

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(400, "VALIDATION_FAILED", "This email is already registered.", "email");
        }
        if (users.existsByStudentNumber(studentNumber)) {
            throw new ApiException(400, "VALIDATION_FAILED", "This student number is already registered.", "studentNumber");
        }

        User user = new User();
        user.setStudentNumber(studentNumber);
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhone(request.phone() == null ? null : request.phone().trim());
        user.setRole("student");

        User saved = users.save(user);
        return new RegisterResponse(saved.getUserId(), saved.getFullName(), saved.getRole());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = verifyCredentials(request.email(), request.password());
        return jwtService.createLoginResponse(user);
    }

    @Transactional(readOnly = true)
    public UserSummary currentUser(String email) {
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Your session is no longer valid.", null));
        return new UserSummary(user.getUserId(), user.getFullName(), user.getRole());
    }

    // -------------------------------------------------------------------
    // Two-step login (students only) - see docs/api-contract.md's
    // "Two-step student login". login() above is untouched and still used
    // as-is by every staff role; this sits in front of it for students,
    // calling jwtService.createLoginResponse() itself once the code is
    // verified rather than calling login() again, since the password has
    // already been checked once by requestLoginCode().
    // -------------------------------------------------------------------

    @Transactional
    public PendingLoginResponse requestLoginCode(LoginRequest request) {
        User user = verifyCredentials(request.email(), request.password());
        if (!"student".equals(user.getRole())) {
            // Same generic failure as a wrong password - a caller must not
            // be able to use this endpoint to discover which accounts are
            // students and which are staff.
            throw invalidCredentials();
        }

        enforceResendCooldown(loginCodes.findTopByUserIdOrderByLastSentAtDesc(user.getUserId()).orElse(null));

        String code = generateCode();
        Instant now = Instant.now();
        LoginVerificationCode entity = new LoginVerificationCode();
        entity.setPendingLoginId(UUID.randomUUID().toString());
        entity.setUserId(user.getUserId());
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(now.plus(CODE_TTL));
        entity.setAttempts(0);
        entity.setLastSentAt(now);
        loginCodes.save(entity);

        emailService.sendLoginCode(user.getEmail(), code);

        return new PendingLoginResponse(entity.getPendingLoginId(), maskEmail(user.getEmail()));
    }

    @Transactional
    public PendingLoginResponse resendLoginCode(String pendingLoginId) {
        LoginVerificationCode entity = loginCodes.findByPendingLoginId(pendingLoginId)
            .orElseThrow(this::invalidLoginAttempt);
        enforceResendCooldown(entity);

        User user = users.findById(entity.getUserId()).orElseThrow(this::invalidLoginAttempt);

        String code = generateCode();
        Instant now = Instant.now();
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(now.plus(CODE_TTL));
        entity.setAttempts(0);
        entity.setLastSentAt(now);
        loginCodes.save(entity);

        emailService.sendLoginCode(user.getEmail(), code);

        return new PendingLoginResponse(entity.getPendingLoginId(), maskEmail(user.getEmail()));
    }

    @Transactional
    public LoginResponse verifyLoginCode(String pendingLoginId, String code) {
        LoginVerificationCode entity = loginCodes.findByPendingLoginId(pendingLoginId)
            .orElseThrow(this::invalidLoginAttempt);

        if (Instant.now().isAfter(entity.getExpiresAt())) {
            loginCodes.delete(entity);
            throw new ApiException(400, "CODE_EXPIRED", "That code has expired. Request a new one.", "code");
        }

        if (entity.getAttempts() >= MAX_CODE_ATTEMPTS) {
            loginCodes.delete(entity);
            throw new ApiException(429, "TOO_MANY_ATTEMPTS", "Too many incorrect attempts. Request a new code.", "code");
        }

        if (!passwordEncoder.matches(code, entity.getCodeHash())) {
            entity.setAttempts(entity.getAttempts() + 1);
            loginCodes.save(entity);
            throw new ApiException(400, "INVALID_CODE", "That code is incorrect.", "code");
        }

        User user = users.findById(entity.getUserId()).orElseThrow(this::invalidLoginAttempt);
        loginCodes.delete(entity);
        return jwtService.createLoginResponse(user);
    }

    private void enforceResendCooldown(LoginVerificationCode mostRecent) {
        if (mostRecent == null) return;
        if (mostRecent.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(Instant.now())) {
            throw new ApiException(429, "TOO_MANY_REQUESTS", "Please wait before requesting another code.", null);
        }
    }

    private ApiException invalidLoginAttempt() {
        return new ApiException(400, "INVALID_LOGIN_ATTEMPT", "Start signing in again.", null);
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** jo***@ufh.ac.za - never lets the full local part reach the response. */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return email;
        String local = email.substring(0, at);
        String domain = email.substring(at);
        int visible = Math.min(2, local.length());
        return local.substring(0, visible) + "*".repeat(Math.max(1, local.length() - visible)) + domain;
    }

    /**
     * login.html has always accepted "a student number or an email" per its
     * own label; before this, only the email half was ever wired up, so
     * typing a student number failed with a generic "invalid credentials" -
     * indistinguishable from a wrong password, so nobody could tell why.
     * A bare student number (digits only, as enforced at registration) looks
     * up by studentNumber; anything else is treated as an email, exactly as
     * before.
     */
    private User verifyCredentials(String rawIdentifier, String password) {
        String identifier = rawIdentifier.trim();
        User user = (identifier.matches("\\d+")
                ? users.findByStudentNumber(identifier)
                : users.findByEmailIgnoreCase(identifier.toLowerCase()))
            .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return user;
    }

    private ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "Invalid email or password.", null);
    }
}
