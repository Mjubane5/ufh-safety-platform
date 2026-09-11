package za.ac.ufh.safety.responders;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification for NearestResponderSelector, written before the code.
 *
 * These were written before the code, as the specification NearestResponderSelector
 * had to satisfy. They all pass now.
 *
 * Coordinates are around the Alice campus. Incident sits at the library.
 */
class NearestResponderSelectorTest {

    private static final double LIBRARY_LAT = -32.78331;
    private static final double LIBRARY_LON = 26.84971;

    private Responder responder(long id, ResponderStatus status, Double lat, Double lon) {
        Responder responder = new Responder();
        responder.setResponderId(id);
        responder.setTeam("campus_security");
        responder.setStatus(status);
        responder.setLatitude(lat);
        responder.setLongitude(lon);
        return responder;
    }

    @Test
    void picksTheCloserOfTwoAvailableResponders() {
        Responder near = responder(1, ResponderStatus.AVAILABLE, -32.78340, 26.84980);
        Responder far  = responder(2, ResponderStatus.AVAILABLE, -32.79500, 26.86000);

        Optional<Responder> chosen =
            NearestResponderSelector.choose(List.of(far, near), LIBRARY_LAT, LIBRARY_LON);

        assertThat(chosen).contains(near);
    }

    @Test
    void ignoresResponderaWhoAreNotAvailable() {
        Responder busyButClose = responder(1, ResponderStatus.ASSIGNED, -32.78332, 26.84972);
        Responder freeButFar   = responder(2, ResponderStatus.AVAILABLE, -32.79500, 26.86000);

        Optional<Responder> chosen =
            NearestResponderSelector.choose(List.of(busyButClose, freeButFar), LIBRARY_LAT, LIBRARY_LON);

        assertThat(chosen).contains(freeButFar);
    }

    @Test
    void ignoresOffDutyResponders() {
        Responder offDuty = responder(1, ResponderStatus.OFF_DUTY, -32.78332, 26.84972);

        Optional<Responder> chosen =
            NearestResponderSelector.choose(List.of(offDuty), LIBRARY_LAT, LIBRARY_LON);

        assertThat(chosen).isEmpty();
    }

    // A responder who has never checked in has no position, so there is no
    // distance to compare. They are not a candidate, however free they are.
    @Test
    void ignoresAnAvailableResponderWithNoKnownPosition() {
        Responder noPosition = responder(1, ResponderStatus.AVAILABLE, null, null);
        Responder located    = responder(2, ResponderStatus.AVAILABLE, -32.79500, 26.86000);

        Optional<Responder> chosen =
            NearestResponderSelector.choose(List.of(noPosition, located), LIBRARY_LAT, LIBRARY_LON);

        assertThat(chosen).contains(located);
    }

    @Test
    void returnsEmptyWhenNobodyQualifies() {
        Optional<Responder> chosen =
            NearestResponderSelector.choose(List.of(), LIBRARY_LAT, LIBRARY_LON);

        assertThat(chosen).isEmpty();
    }

    /**
     * The one that catches a naive implementation.
     *
     * `east` is 0.0100 degrees of longitude away, `south` is 0.0095 degrees of
     * latitude away. Compare the raw numbers and south looks closer. But at
     * 32.8 degrees south a degree of longitude covers only about 0.84 of the
     * ground a degree of latitude does, so east is really about 0.0084
     * latitude-equivalents away — it is the closer one.
     *
     * If this fails and the others pass, the cosine factor is missing.
     */
    @Test
    void accountsForLongitudeDegreesBeingNarrowerThisFarSouth() {
        Responder east  = responder(1, ResponderStatus.AVAILABLE, LIBRARY_LAT, LIBRARY_LON + 0.0100);
        Responder south = responder(2, ResponderStatus.AVAILABLE, LIBRARY_LAT - 0.0095, LIBRARY_LON);

        Optional<Responder> chosen =
            NearestResponderSelector.choose(List.of(south, east), LIBRARY_LAT, LIBRARY_LON);

        assertThat(chosen).contains(east);
    }
}
