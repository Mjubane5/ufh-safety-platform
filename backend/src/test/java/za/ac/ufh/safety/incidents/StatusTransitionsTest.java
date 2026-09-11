package za.ac.ufh.safety.incidents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table on its own. These are the rules a panel is most likely
 * to ask about, so they are tested without any mocking to get in the way.
 */
class StatusTransitionsTest {

    @Test
    void theHappyPathRunsAllTheWayToResolved() {
        assertThat(StatusTransitions.isLegal(IncidentStatus.REPORTED, IncidentStatus.TRIAGED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.TRIAGED, IncidentStatus.ASSIGNED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.ASSIGNED, IncidentStatus.EN_ROUTE)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.EN_ROUTE, IncidentStatus.ON_SCENE)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.ON_SCENE, IncidentStatus.RESOLVED)).isTrue();
    }

    // The contract names this case explicitly: resolved must not go back.
    @Test
    void aResolvedIncidentCannotBeReopened() {
        assertThat(StatusTransitions.isLegal(IncidentStatus.RESOLVED, IncidentStatus.REPORTED)).isFalse();
        assertThat(StatusTransitions.isLegal(IncidentStatus.RESOLVED, IncidentStatus.ON_SCENE)).isFalse();
        assertThat(StatusTransitions.isTerminal(IncidentStatus.RESOLVED)).isTrue();
    }

    @Test
    void aCancelledIncidentIsTerminalToo() {
        assertThat(StatusTransitions.isTerminal(IncidentStatus.CANCELLED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.CANCELLED, IncidentStatus.ASSIGNED)).isFalse();
    }

    // A false alarm can surface at any point before the incident closes.
    @Test
    void everyLiveStatusCanStillBeCancelled() {
        assertThat(StatusTransitions.isLegal(IncidentStatus.REPORTED, IncidentStatus.CANCELLED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.TRIAGED, IncidentStatus.CANCELLED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.ASSIGNED, IncidentStatus.CANCELLED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.EN_ROUTE, IncidentStatus.CANCELLED)).isTrue();
        assertThat(StatusTransitions.isLegal(IncidentStatus.ON_SCENE, IncidentStatus.CANCELLED)).isTrue();
    }

    @Test
    void stepsCannotBeSkipped() {
        assertThat(StatusTransitions.isLegal(IncidentStatus.REPORTED, IncidentStatus.ON_SCENE)).isFalse();
        assertThat(StatusTransitions.isLegal(IncidentStatus.ASSIGNED, IncidentStatus.RESOLVED)).isFalse();
    }
}
