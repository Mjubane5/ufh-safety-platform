package za.ac.ufh.safety.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.responders.Responder;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DemoDataSeederTest {

    private UserRepository userRepository;
    private ResponderRepository responderRepository;
    private PasswordEncoder passwordEncoder;
    private DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        responderRepository = mock(ResponderRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-test-password");

        // When saving a user, simulate setting a generated userId if missing
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            if (u.getUserId() == null) {
                ReflectionTestUtils.setField(u, "userId", 101L);
            }
            return u;
        });

        seeder = new DemoDataSeeder(userRepository, responderRepository, passwordEncoder);
    }

    @Test
    void seedsUsersAndRespondersWhenEmpty() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(responderRepository.existsById(anyLong())).thenReturn(false);

        seeder.run();

        // 3 students + 4 staff (campus_control, gbv_officer, scu_officer,
        // health_officer) + 4 responders = 11 users
        verify(userRepository, times(11)).save(any(User.class));
        // 4 responders
        verify(responderRepository, times(4)).save(any(Responder.class));
    }

    @Test
    void skipsSeedingWhenUsersAlreadyExist() {
        User existing = new User();
        ReflectionTestUtils.setField(existing, "userId", 1L);
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(existing));
        when(responderRepository.existsById(anyLong())).thenReturn(true);

        seeder.run();

        verify(userRepository, never()).save(any(User.class));
        verify(responderRepository, never()).save(any(Responder.class));
    }

    @Test
    void doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(seeder, "seedEnabled", false);

        seeder.run();

        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        verify(userRepository, never()).save(any(User.class));
    }
}
