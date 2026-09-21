package za.ac.ufh.safety.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
}
