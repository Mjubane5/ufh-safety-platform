package za.ac.ufh.safety.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String PASSWORD = "correct-horse";

    private UserRepository users;
    private PasswordEncoder passwordEncoder;
    private LoginVerificationCodeRepository loginCodes;
    private EmailService emailService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        loginCodes = mock(LoginVerificationCodeRepository.class);
        emailService = mock(EmailService.class);
        authService = new AuthService(users, passwordEncoder, new JwtService(
            "test-only-signing-key-at-least-32-characters", 120), loginCodes, emailService);

        // requestLoginCode/resendLoginCode save through this mock; hand the
        // same instance back so later calls in the same test see the state
        // the previous call wrote to it, exactly like a real repository would.
        when(loginCodes.save(any(LoginVerificationCode.class))).thenAnswer(call -> call.getArgument(0));
    }

    private RegisterRequest registration() {
        return new RegisterRequest("202512345", "A Student", "202512345@UFH.ac.za", PASSWORD, "0821234567");
    }

    private User storedStudent() {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 17L);
        user.setEmail("202512345@ufh.ac.za");
        user.setFullName("A Student");
        user.setRole("student");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return user;
    }

    private User storedStaff() {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 101L);
        user.setEmail("control@ufh.ac.za");
        user.setFullName("A Campus Control Officer");
        user.setRole("campus_control");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return user;
    }

    @Test
    void storesAHashRatherThanThePassword() {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(users.existsByStudentNumber(anyString())).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        authService.register(registration());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());

        assertThat(saved.getValue().getPasswordHash())
            .isNotEqualTo(PASSWORD)
            .startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void alwaysRegistersAsStudentAndNormalisesTheEmail() {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(users.existsByStudentNumber(anyString())).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        RegisterResponse response = authService.register(registration());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());

        // The role comes from the server, never from the request body, so a
        // caller cannot register themselves as an admin.
        assertThat(saved.getValue().getRole()).isEqualTo("student");
        assertThat(response.role()).isEqualTo("student");
        assertThat(saved.getValue().getEmail()).isEqualTo("202512345@ufh.ac.za");
    }

    @Test
    void namesTheFieldWhenTheEmailIsTaken() {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registration()))
            .isInstanceOf(ApiException.class)
            .satisfies(thrown -> {
                ApiException ex = (ApiException) thrown;
                assertThat(ex.getStatus()).isEqualTo(400);
                assertThat(ex.getField()).isEqualTo("email");
            });
    }

    @Test
    void tellsUnknownEmailAndWrongPasswordApart_toNobody() {
        // The contract asks for one message for both, so a stranger cannot use
        // the login form to discover which addresses are registered.
        when(users.findByEmailIgnoreCase("nobody@ufh.ac.za")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("202512345@ufh.ac.za")).thenReturn(Optional.of(storedStudent()));

        ApiException unknownEmail = catchApiException(
            () -> authService.login(new LoginRequest("nobody@ufh.ac.za", PASSWORD)));
        ApiException wrongPassword = catchApiException(
            () -> authService.login(new LoginRequest("202512345@ufh.ac.za", "not-the-password")));

        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownEmail.getCode()).isEqualTo(wrongPassword.getCode());
        assertThat(unknownEmail.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getField()).isNull();
    }

    @Test
    void signsInWithTheRightPassword() {
        when(users.findByEmailIgnoreCase("202512345@ufh.ac.za")).thenReturn(Optional.of(storedStudent()));

        LoginResponse response = authService.login(new LoginRequest("  202512345@UFH.ac.za  ", PASSWORD));

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().fullName()).isEqualTo("A Student");
    }

    // login.html has always labelled this field "student number or email" -
    // these confirm the student-number half of that promise is now actually
    // wired up, not just the email half.

    @Test
    void signsInWithAStudentNumberInsteadOfAnEmail() {
        when(users.findByStudentNumber("202512345")).thenReturn(Optional.of(storedStudent()));

        LoginResponse response = authService.login(new LoginRequest("  202512345  ", PASSWORD));

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().fullName()).isEqualTo("A Student");
        verify(users, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void tellsUnknownStudentNumberAndWrongPasswordApart_toNobody() {
        when(users.findByStudentNumber("999999999")).thenReturn(Optional.empty());
        when(users.findByStudentNumber("202512345")).thenReturn(Optional.of(storedStudent()));

        ApiException unknownNumber = catchApiException(
            () -> authService.login(new LoginRequest("999999999", PASSWORD)));
        ApiException wrongPassword = catchApiException(
            () -> authService.login(new LoginRequest("202512345", "not-the-password")));

        assertThat(unknownNumber.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownNumber.getCode()).isEqualTo(wrongPassword.getCode());
        assertThat(unknownNumber.getStatus()).isEqualTo(401);
    }

    @Test
    void requestLoginCodeAlsoAcceptsAStudentNumber() {
        when(users.findByStudentNumber("202512345")).thenReturn(Optional.of(storedStudent()));

        PendingLoginResponse response = authService.requestLoginCode(new LoginRequest("202512345", PASSWORD));

        assertThat(response.pendingLoginId()).isNotBlank();
        // The masked value shown back is always the account's real email,
        // regardless of which identifier shape was used to sign in.
        assertThat(response.maskedEmail()).isEqualTo("20*******@ufh.ac.za");
        verify(emailService).sendLoginCode(eq("202512345@ufh.ac.za"), anyString());
    }

    // --- Two-step login: requestLoginCode ---------------------------------

    @Test
    void requestLoginCodeEmailsACodeAndMasksTheEmailInTheResponse() {
        when(users.findByEmailIgnoreCase("202512345@ufh.ac.za")).thenReturn(Optional.of(storedStudent()));

        PendingLoginResponse response = authService.requestLoginCode(new LoginRequest("202512345@ufh.ac.za", PASSWORD));

        assertThat(response.pendingLoginId()).isNotBlank();
        // "202512345" -> first 2 chars visible, the remaining 7 masked.
        assertThat(response.maskedEmail()).isEqualTo("20*******@ufh.ac.za");

        ArgumentCaptor<String> emailedCode = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendLoginCode(anyString(), emailedCode.capture());
        assertThat(emailedCode.getValue()).matches("\\d{6}");
    }

    @Test
    void requestLoginCodeStoresAHashNotTheCodeItself() {
        when(users.findByEmailIgnoreCase("202512345@ufh.ac.za")).thenReturn(Optional.of(storedStudent()));

        authService.requestLoginCode(new LoginRequest("202512345@ufh.ac.za", PASSWORD));

        ArgumentCaptor<LoginVerificationCode> saved = ArgumentCaptor.forClass(LoginVerificationCode.class);
        verify(loginCodes).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).startsWith("$2");
    }

    @Test
    void requestLoginCodeRejectsAStaffAccountTheSameWayAsWrongPassword() {
        when(users.findByEmailIgnoreCase("control@ufh.ac.za")).thenReturn(Optional.of(storedStaff()));

        ApiException ex = catchApiException(() -> authService.requestLoginCode(new LoginRequest("control@ufh.ac.za", PASSWORD)));

        assertThat(ex.getStatus()).isEqualTo(401);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
        verify(emailService, never()).sendLoginCode(anyString(), anyString());
    }

    @Test
    void requestLoginCodeRejectsAWrongPasswordWithoutEmailingAnything() {
        when(users.findByEmailIgnoreCase("202512345@ufh.ac.za")).thenReturn(Optional.of(storedStudent()));

        assertThatThrownBy(() -> authService.requestLoginCode(new LoginRequest("202512345@ufh.ac.za", "wrong")))
            .isInstanceOf(ApiException.class)
            .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(401));
        verify(emailService, never()).sendLoginCode(anyString(), anyString());
    }

    @Test
    void requestLoginCodeIsRateLimitedRightAfterAPreviousOne() {
        when(users.findByEmailIgnoreCase("202512345@ufh.ac.za")).thenReturn(Optional.of(storedStudent()));
        LoginVerificationCode recent = new LoginVerificationCode();
        recent.setLastSentAt(Instant.now());
        when(loginCodes.findTopByUserIdOrderByLastSentAtDesc(17L)).thenReturn(Optional.of(recent));

        ApiException ex = catchApiException(() -> authService.requestLoginCode(new LoginRequest("202512345@ufh.ac.za", PASSWORD)));

        assertThat(ex.getStatus()).isEqualTo(429);
        assertThat(ex.getCode()).isEqualTo("TOO_MANY_REQUESTS");
    }

    // --- Two-step login: verifyLoginCode -----------------------------------

    private LoginVerificationCode pendingCodeFor(User user, String rawCode) {
        LoginVerificationCode entity = new LoginVerificationCode();
        entity.setPendingLoginId("pending-123");
        entity.setUserId(user.getUserId());
        entity.setCodeHash(passwordEncoder.encode(rawCode));
        entity.setExpiresAt(Instant.now().plusSeconds(300));
        entity.setAttempts(0);
        entity.setLastSentAt(Instant.now());
        return entity;
    }

    @Test
    void verifyLoginCodeSignsInWithTheRightCodeAndConsumesIt() {
        User student = storedStudent();
        LoginVerificationCode pending = pendingCodeFor(student, "483920");
        when(loginCodes.findByPendingLoginId("pending-123")).thenReturn(Optional.of(pending));
        when(users.findById(17L)).thenReturn(Optional.of(student));

        LoginResponse response = authService.verifyLoginCode("pending-123", "483920");

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().fullName()).isEqualTo("A Student");
        verify(loginCodes).delete(pending);
    }

    @Test
    void verifyLoginCodeRejectsTheWrongCodeAndCountsTheAttempt() {
        User student = storedStudent();
        LoginVerificationCode pending = pendingCodeFor(student, "483920");
        when(loginCodes.findByPendingLoginId("pending-123")).thenReturn(Optional.of(pending));

        ApiException ex = catchApiException(() -> authService.verifyLoginCode("pending-123", "000000"));

        assertThat(ex.getStatus()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("INVALID_CODE");
        assertThat(pending.getAttempts()).isEqualTo(1);
        verify(loginCodes, never()).delete(any());
    }

    @Test
    void verifyLoginCodeExpiresAfterFiveMinutes() {
        User student = storedStudent();
        LoginVerificationCode pending = pendingCodeFor(student, "483920");
        pending.setExpiresAt(Instant.now().minusSeconds(1));
        when(loginCodes.findByPendingLoginId("pending-123")).thenReturn(Optional.of(pending));

        ApiException ex = catchApiException(() -> authService.verifyLoginCode("pending-123", "483920"));

        assertThat(ex.getStatus()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("CODE_EXPIRED");
        verify(loginCodes).delete(pending);
    }

    @Test
    void verifyLoginCodeLocksOutAfterFiveWrongAttempts() {
        User student = storedStudent();
        LoginVerificationCode pending = pendingCodeFor(student, "483920");
        pending.setAttempts(5);
        when(loginCodes.findByPendingLoginId("pending-123")).thenReturn(Optional.of(pending));

        ApiException ex = catchApiException(() -> authService.verifyLoginCode("pending-123", "483920"));

        assertThat(ex.getStatus()).isEqualTo(429);
        assertThat(ex.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
        verify(loginCodes).delete(pending);
    }

    @Test
    void verifyLoginCodeRejectsAnUnknownPendingLoginId() {
        when(loginCodes.findByPendingLoginId("does-not-exist")).thenReturn(Optional.empty());

        ApiException ex = catchApiException(() -> authService.verifyLoginCode("does-not-exist", "483920"));

        assertThat(ex.getStatus()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("INVALID_LOGIN_ATTEMPT");
    }

    // --- Two-step login: resendLoginCode -----------------------------------

    @Test
    void resendLoginCodeIssuesADifferentCodeAndResetsAttempts() {
        User student = storedStudent();
        LoginVerificationCode pending = pendingCodeFor(student, "483920");
        pending.setAttempts(3);
        pending.setLastSentAt(Instant.now().minusSeconds(60));
        when(loginCodes.findByPendingLoginId("pending-123")).thenReturn(Optional.of(pending));
        when(users.findById(17L)).thenReturn(Optional.of(student));

        PendingLoginResponse response = authService.resendLoginCode("pending-123");

        assertThat(response.pendingLoginId()).isEqualTo("pending-123");
        assertThat(pending.getAttempts()).isEqualTo(0);
        assertThat(passwordEncoder.matches("483920", pending.getCodeHash())).isFalse();
    }

    @Test
    void resendLoginCodeIsRateLimitedRightAfterTheLastOne() {
        User student = storedStudent();
        LoginVerificationCode pending = pendingCodeFor(student, "483920");
        pending.setLastSentAt(Instant.now());
        when(loginCodes.findByPendingLoginId("pending-123")).thenReturn(Optional.of(pending));

        ApiException ex = catchApiException(() -> authService.resendLoginCode("pending-123"));

        assertThat(ex.getStatus()).isEqualTo(429);
        assertThat(ex.getCode()).isEqualTo("TOO_MANY_REQUESTS");
    }

    private static ApiException catchApiException(Runnable call) {
        try {
            call.run();
            throw new AssertionError("Expected an ApiException but the call succeeded.");
        } catch (ApiException ex) {
            return ex;
        }
    }
}
