package za.ac.ufh.safety.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
        String email = request.email().trim().toLowerCase();
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(this::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        return jwtService.createLoginResponse(user);
    }

    @Transactional(readOnly = true)
    public UserSummary currentUser(String email) {
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Your session is no longer valid.", null));
        return new UserSummary(user.getUserId(), user.getFullName(), user.getRole());
    }

    private ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "Invalid email or password.", null);
    }
}
