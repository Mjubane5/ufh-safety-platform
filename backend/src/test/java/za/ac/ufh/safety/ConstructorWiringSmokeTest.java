package za.ac.ufh.safety;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import za.ac.ufh.safety.incidents.IncidentAssignmentService;
import za.ac.ufh.safety.incidents.IncidentRepository;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.safetywalk.CppRouteEngine;
import za.ac.ufh.safety.safetywalk.GeometricRouteEngine;
import za.ac.ufh.safety.safetywalk.HotspotRepository;
import za.ac.ufh.safety.safetywalk.SafeRouteService;
import za.ac.ufh.safety.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A real Spring bean context, not just direct constructor calls - this is
 * exactly what the unit tests for SafeRouteService and
 * IncidentAssignmentService do NOT exercise, since they call
 * `new XxxService(...)` directly rather than asking Spring to resolve it.
 *
 * Both of those classes have two constructors (one for Spring, a
 * package-private one for tests) and neither had @Autowired - which looks
 * harmless and passed all 205 other tests, but Spring will not reliably
 * pick "the public one" once more than one constructor exists; without an
 * explicit @Autowired it looks for a genuine no-arg constructor instead and
 * refuses to start. This crashed production once already. This test's only
 * job is to make sure that can never happen silently again.
 */
class ConstructorWiringSmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(IncidentRepository.class, () -> mock(IncidentRepository.class))
            .withBean(ResponderRepository.class, () -> mock(ResponderRepository.class))
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(HotspotRepository.class, () -> mock(HotspotRepository.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            // Constructed by Spring itself (reflection, package-private
            // constructor and all - visibility across packages is only a
            // problem for this test's own code, not for Spring), so this
            // exercises the exact same instantiation path production uses,
            // @Value placeholder resolution included.
            .withBean(CppRouteEngine.class)
            .withBean(GeometricRouteEngine.class)
            .withUserConfiguration(IncidentAssignmentService.class, SafeRouteService.class);

    @Test
    void springCanActuallyConstructBothRoutingConsumers() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IncidentAssignmentService.class);
            assertThat(context).hasSingleBean(SafeRouteService.class);
        });
    }
}
