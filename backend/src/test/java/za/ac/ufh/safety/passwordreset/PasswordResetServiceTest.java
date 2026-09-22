package za.ac.ufh.safety.passwordreset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.auth.EmailService;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private PasswordResetTokenRepository tokens;
    private UserRepository users;
    private PasswordEncoder passwordEncoder;
    private EmailService emailService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        tokens = mock(PasswordResetTokenRepository.class);
        users = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailService = mock(EmailService.class);
        service = new PasswordResetService(tokens, users, passwordEncoder, emailService);
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- requestReset: never reveals whether the email is registered ------

    @Test
    void aRegisteredEmailGetsATokenAndAnEmail() {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 17L);
        user.setEmail("student@ufh.ac.za");
        when(users.findByEmailIgnoreCase("student@ufh.ac.za")).thenReturn(Optional.of(user));

        service.requestReset(new ForgotPasswordRequest("student@ufh.ac.za"), "https://example.com");

        verify(emailService).sendPasswordResetLink(eq("student@ufh.ac.za"), contains("https://example.com/reset-password.html?token="));
        verify(tokens).save(any(PasswordResetToken.class));
    }

    @Test
    void anUnregisteredEmailSendsNothingButLooksTheSame() {
        when(users.findByEmailIgnoreCase("ghost@ufh.ac.za")).thenReturn(Optional.empty());

        MessageResponse response = service.requestReset(new ForgotPasswordRequest("ghost@ufh.ac.za"), "https://example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
        verify(tokens, never()).save(any());
        assertThat(response.message()).isEqualTo("If that email is registered, we've sent a reset link.");
    }

    @Test
    void theResponseIsIdenticalForKnownAndUnknownEmails() {
        when(users.findByEmailIgnoreCase("ghost@ufh.ac.za")).thenReturn(Optional.empty());
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 17L);
        user.setEmail("student@ufh.ac.za");
        when(users.findByEmailIgnoreCase("student@ufh.ac.za")).thenReturn(Optional.of(user));

        MessageResponse known = service.requestReset(new ForgotPasswordRequest("student@ufh.ac.za"), "https://example.com");
        MessageResponse unknown = service.requestReset(new ForgotPasswordRequest("ghost@ufh.ac.za"), "https://example.com");

        assertThat(known.message()).isEqualTo(unknown.message());
    }

    // --- resetPassword ------------------------------------------------------

    private PasswordResetToken storedToken(String rawToken, Instant expiresAt, Instant usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        ReflectionTestUtils.setField(token, "tokenId", 1L);
        token.setUserId(17L);
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        when(tokens.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void aValidTokenUpdatesThePasswordAndMarksTheTokenUsed() {
        storedToken("good-token", Instant.now().plusSeconds(3600), null);
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 17L);
        when(users.findById(17L)).thenReturn(Optional.of(user));

        MessageResponse response = service.resetPassword(new ResetPasswordRequest("good-token", "NewPassword123!"));

        assertThat(response.message()).contains("Password updated");
        verify(passwordEncoder).encode("NewPassword123!");
        var captor = forClass(PasswordResetToken.class);
        verify(tokens, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getUsedAt()).isNotNull();
    }

    @Test
    void anUnknownTokenIsRejectedGenerically() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest("no-such-token", "NewPassword123!")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 400)
            .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");
    }

    @Test
    void anExpiredTokenIsRejectedWithTheSameGenericError() {
        storedToken("expired-token", Instant.now().minusSeconds(60), null);

        ApiException ex = (ApiException) catchThrowable(() ->
            service.resetPassword(new ResetPasswordRequest("expired-token", "NewPassword123!")));

        assertThat(ex.getStatus()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void anAlreadyUsedTokenIsRejectedWithTheSameGenericError() {
        storedToken("used-token", Instant.now().plusSeconds(3600), Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest("used-token", "NewPassword123!")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");
    }

    @Test
    void expiredAndUnknownTokensProduceTheExactSameMessage() {
        storedToken("expired-token", Instant.now().minusSeconds(60), null);

        ApiException expired = (ApiException) catchThrowable(() ->
            service.resetPassword(new ResetPasswordRequest("expired-token", "NewPassword123!")));
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());
        ApiException unknown = (ApiException) catchThrowable(() ->
            service.resetPassword(new ResetPasswordRequest("totally-unknown", "NewPassword123!")));

        assertThat(expired.getMessage()).isEqualTo(unknown.getMessage());
    }
}
