package za.ac.ufh.safety.incidents;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The enums exist to stop typos, but only if their wire values match
 * docs/api-contract.md exactly. These tests pin them to the contract, so
 * renaming a constant carelessly breaks the build rather than the frontend.
 */
class IncidentEnumTest {

    @Test
    void typeWireValuesMatchTheContract() {
        List<String> expected = List.of(
                "sos", "medical", "fire", "theft", "assault",
                "accident", "suspicious", "unsafe", "other");

        List<String> actual = Stream.of(IncidentType.values())
                .map(IncidentType::wireValue)
                .toList();

        assertEquals(expected, actual);
    }

    @Test
    void statusWireValuesMatchTheContract() {
        List<String> expected = List.of(
                "reported", "triaged", "assigned",
                "en_route", "on_scene", "resolved", "cancelled");

        List<String> actual = Stream.of(IncidentStatus.values())
                .map(IncidentStatus::wireValue)
                .toList();

        assertEquals(expected, actual);
    }

    @Test
    void underscoreStatusesDoNotUseTheJavaConstantName() {
        // The trap this whole conversion exists to avoid: @Enumerated(STRING)
        // would have stored "EN_ROUTE" and broken the contract.
        assertEquals("en_route", IncidentStatus.EN_ROUTE.wireValue());
        assertEquals("on_scene", IncidentStatus.ON_SCENE.wireValue());
    }

    @Test
    void wireValuesRoundTrip() {
        for (IncidentType type : IncidentType.values()) {
            assertEquals(type, IncidentType.fromWireValue(type.wireValue()));
        }
        for (IncidentStatus status : IncidentStatus.values()) {
            assertEquals(status, IncidentStatus.fromWireValue(status.wireValue()));
        }
    }

    @Test
    void unknownWireValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IncidentType.fromWireValue("MEDICAL"));
        assertThrows(IllegalArgumentException.class, () -> IncidentStatus.fromWireValue("EN_ROUTE"));
    }

    @Test
    void convertersUseWireValues() {
        assertEquals("en_route", new IncidentStatusConverter().convertToDatabaseColumn(IncidentStatus.EN_ROUTE));
        assertEquals(IncidentStatus.EN_ROUTE, new IncidentStatusConverter().convertToEntityAttribute("en_route"));
        assertEquals("sos", new IncidentTypeConverter().convertToDatabaseColumn(IncidentType.SOS));
        assertEquals(IncidentType.SOS, new IncidentTypeConverter().convertToEntityAttribute("sos"));
        assertNull(new IncidentTypeConverter().convertToDatabaseColumn(null));
        assertNull(new IncidentTypeConverter().convertToEntityAttribute(null));
    }
}
