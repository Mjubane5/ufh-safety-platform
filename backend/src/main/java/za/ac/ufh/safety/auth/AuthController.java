package za.ac.ufh.safety.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import za.ac.ufh.safety.passwordreset.ForgotPasswordRequest;
import za.ac.ufh.safety.passwordreset.MessageResponse;
import za.ac.ufh.safety.passwordreset.PasswordResetService;
import za.ac.ufh.safety.passwordreset.ResetPasswordRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/login/request-code")
    public PendingLoginResponse requestLoginCode(@Valid @RequestBody LoginRequest request) {
        return authService.requestLoginCode(request);
    }

    @PostMapping("/login/resend-code")
    public PendingLoginResponse resendLoginCode(@Valid @RequestBody ResendLoginCodeRequest request) {
        return authService.resendLoginCode(request.pendingLoginId());
    }

    @PostMapping("/login/verify-code")
    public LoginResponse verifyLoginCode(@Valid @RequestBody VerifyLoginCodeRequest request) {
        return authService.verifyLoginCode(request.pendingLoginId(), request.code());
    }

    @GetMapping("/me")
    public UserSummary me(Authentication authentication) {
        return authService.currentUser(authentication.getName());
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        return passwordResetService.requestReset(request, frontendBaseUrl(httpRequest));
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return passwordResetService.resetPassword(request);
    }

    // The hosted build serves the frontend from this same origin (see
    // config.js's own comment on BASE_URL), so the incoming request's own
    // scheme/host is the correct place to send a reset link back to -
    // there is no separate "frontend URL" to configure.
    private String frontendBaseUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
        boolean isDefaultPort = ("http".equals(request.getScheme()) && request.getServerPort() == 80)
            || ("https".equals(request.getScheme()) && request.getServerPort() == 443);
        if (!isDefaultPort) {
            url.append(':').append(request.getServerPort());
        }
        return url.toString();
    }
}
