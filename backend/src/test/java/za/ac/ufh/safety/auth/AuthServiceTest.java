package za.ac.ufh.safety.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String PASSWORD = "correct-horse";

    private UserRepository users;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(users, passwordEncoder, new JwtService(
            "test-only-signing-key-at-least-32-characters", 120));
    }

    private RegisterRequest registration() {
        return new RegisterRequest("202512345", "A Student", "202512345@UFH.ac.za", PASSWORD, "0821234567");
    }

    private User storedStudent() {
        User user = new User();
        user.setEmail("202512345@ufh.ac.za");
        user.setFullName("A Student");
        user.setRole("student");
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

    private static ApiException catchApiException(Runnable call) {
        try {
            call.run();
            throw new AssertionError("Expected an ApiException but the call succeeded.");
        } catch (ApiException ex) {
            return ex;
        }
    }
}
