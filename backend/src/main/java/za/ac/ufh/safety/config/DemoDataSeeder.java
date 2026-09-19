package za.ac.ufh.safety.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.responders.Responder;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.responders.ResponderStatus;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.time.Instant;

/**
 * Automatically provisions synthetic demonstration accounts into the database
 * on application startup if they are not already present.
 *
 * This ensures that both local development and the hosted Railway deployment
 * have the required student, responder, campus control, and GBV officer accounts
 * ready for testing immediately without needing manual SQL script execution.
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEFAULT_PASSWORD = "DevPassword123!";

    private final UserRepository userRepository;
    private final ResponderRepository responderRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.enabled:true}")
    private boolean seedEnabled = true;

    public DemoDataSeeder(
        UserRepository userRepository,
        ResponderRepository responderRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.responderRepository = responderRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Demo data seeding is disabled via app.seed.enabled=false");
            return;
        }

        log.info("Checking and seeding demonstration accounts...");
        String encodedPassword = passwordEncoder.encode(DEFAULT_PASSWORD);

        // 1. Seed Students
        seedUser("201900001", "Thandiwe Mokoena", "thandiwe.mokoena@example.ac.za", "+27710000001", "student", encodedPassword);
        seedUser("201900002", "Sipho Dlamini", "sipho.dlamini@example.ac.za", "+27710000002", "student", encodedPassword);
        seedUser("201900003", "Aphiwe Ngcobo", "aphiwe.ngcobo@example.ac.za", null, "student", encodedPassword);

        // 2. Seed Campus Control & GBV Officer
        seedUser(null, "Johan van Wyk", "johan.vanwyk@example.ac.za", "+27720000002", "campus_control", encodedPassword);
        seedUser(null, "Lerato Mahlangu", "lerato.mahlangu@example.ac.za", "+27720000003", "gbv_officer", encodedPassword);

        // 3. Seed Responders and their duty status/locations
        seedResponder(
            null, "Nomsa Khumalo", "nomsa.khumalo@example.ac.za", "+27720000001", encodedPassword,
            "campus_security", ResponderStatus.AVAILABLE, -32.78210, 26.84800, Instant.parse("2026-08-23T01:49:30Z")
        );
        seedResponder(
            null, "Zanele Mthembu", "zanele.mthembu@example.ac.za", "+27720000004", encodedPassword,
            "medical", ResponderStatus.AVAILABLE, -32.79500, 26.86000, Instant.parse("2026-08-23T01:45:00Z")
        );
        seedResponder(
            null, "Pieter Botha", "pieter.botha@example.ac.za", "+27720000005", encodedPassword,
            "campus_security", ResponderStatus.AVAILABLE, null, null, null
        );
        seedResponder(
            null, "Ayanda Sithole", "ayanda.sithole@example.ac.za", "+27720000006", encodedPassword,
            "campus_security", ResponderStatus.OFF_DUTY, -32.78335, 26.84975, Instant.parse("2026-08-22T18:00:00Z")
        );

        log.info("Demonstration accounts check completed successfully.");
    }

    private User seedUser(
        String studentNumber,
        String fullName,
        String email,
        String phone,
        String role,
        String encodedPassword
    ) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User user = new User();
            user.setStudentNumber(studentNumber);
            user.setFullName(fullName);
            user.setEmail(email.trim().toLowerCase());
            user.setPhone(phone);
            user.setRole(role);
            user.setPasswordHash(encodedPassword);
            User saved = userRepository.save(user);
            log.info("Seeded demo user: {} ({}) with role {}", fullName, email, role);
            return saved;
        });
    }

    private void seedResponder(
        String studentNumber,
        String fullName,
        String email,
        String phone,
        String encodedPassword,
        String team,
        ResponderStatus status,
        Double latitude,
        Double longitude,
        Instant lastSeenAt
    ) {
        User user = seedUser(studentNumber, fullName, email, phone, "responder", encodedPassword);
        if (!responderRepository.existsById(user.getUserId())) {
            Responder responder = new Responder();
            responder.setResponderId(user.getUserId());
            responder.setTeam(team);
            responder.setStatus(status);
            responder.setLatitude(latitude);
            responder.setLongitude(longitude);
            responder.setLastSeenAt(lastSeenAt);
            responderRepository.save(responder);
            log.info("Seeded responder duty record for: {} (team: {}, status: {})", fullName, team, status);
        }
    }
}
