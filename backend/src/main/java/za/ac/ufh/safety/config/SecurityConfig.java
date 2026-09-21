package za.ac.ufh.safety.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import za.ac.ufh.safety.common.ErrorResponse;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtFilter,
        ObjectMapper objectMapper
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Deny by default. Registration and login are the only public
            // endpoints; everything added later is protected until someone
            // deliberately opens it up. Incidents and GBV cases arrive on this
            // chain next, and GBV content is restricted by role.
            .authorizeHttpRequests(auth -> auth
                // The browser sends its CORS preflight without the
                // Authorization header, so it has to pass without a token.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // The hosted build serves the pages from this same
                // application, and deny-by-default covers them too. Without
                // this rule the login page itself answers 401, so nobody can
                // reach the form that would get them a token. Only static
                // files are opened up; every /api path below stays protected.
                .requestMatchers(HttpMethod.GET,
                    "/", "/*.html", "/css/**", "/js/**", "/assets/**", "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/auth/register", "/api/auth/login",
                    "/api/auth/login/request-code", "/api/auth/login/resend-code", "/api/auth/login/verify-code")
                    .permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                response.setContentType("application/json");
                objectMapper.writeValue(response.getWriter(),
                    new ErrorResponse("NOT_AUTHENTICATED", "You are not signed in. Please log in again.", null));
            }))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        // Hosted, the pages come from this same application, so requests are
        // same-origin and never reach this list at all. It exists for local
        // development, where the pages are served from port 5500 and the
        // backend from 8080. CORS_ALLOWED_ORIGINS can add more without a
        // rebuild - a comma-separated list, exact origins only, no wildcard.
        String configured = System.getenv("CORS_ALLOWED_ORIGINS");
        List<String> origins = (configured == null || configured.isBlank())
            ? List.of("http://localhost:5500", "http://127.0.0.1:5500")
            : Arrays.stream(configured.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
