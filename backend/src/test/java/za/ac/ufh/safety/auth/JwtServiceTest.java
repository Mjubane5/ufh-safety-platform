package za.ac.ufh.safety.auth;

import org.junit.jupiter.api.Test;
import za.ac.ufh.safety.user.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-only-signing-key-at-least-32-characters";

    private static User student() {
        User user = new User();
        user.setEmail("202512345@ufh.ac.za");
        user.setFullName("A Student");
        user.setRole("student");
        return user;
    }

    @Test
    void signsATokenItCanReadBack() {
        JwtService service = new JwtService(SECRET, 120);

        LoginResponse response = service.createLoginResponse(student());

        assertThat(service.extractEmail(response.token())).isEqualTo("202512345@ufh.ac.za");
        assertThat(response.user().role()).isEqualTo("student");
        assertThat(response.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() {
        String token = new JwtService(SECRET, 120).createLoginResponse(student()).token();
        JwtService other = new JwtService("a-completely-different-key-32-characters-long", 120);

        assertThatThrownBy(() -> other.extractEmail(token))
            .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void refusesToStartWithoutAUsableSecret() {
        assertThatThrownBy(() -> new JwtService("", 120))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new JwtService("too-short", 120))
            .isInstanceOf(IllegalStateException.class);
    }
}
