package za.ac.ufh.safety.gbv;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Several GBV endpoints are public but behave differently for a signed-in
 * caller (POST /reports records reporterUserId when not anonymous; the
 * public chat endpoints are used unauthenticated by design). Spring
 * Security still sets an Authentication on every request - a real one from
 * JwtAuthenticationFilter when a valid Bearer token was sent, or the
 * framework's own AnonymousAuthenticationToken (principal "anonymousUser")
 * when it was not. This tells the two apart.
 */
final class GbvAuth {

    private GbvAuth() {
    }

    static Optional<String> authenticatedEmail(Authentication authentication) {
        if (authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(authentication.getName());
    }
}
