package za.ac.ufh.safety.incidents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The incident lifecycle from docs/api-contract.md section 3:
 *
 *   reported -> triaged -> assigned -> en_route -> on_scene -> resolved
 *                                                           -> cancelled
 *
 * CANCELLED is the false-alarm path. The row is kept, never deleted.
 */
public enum IncidentStatus {

    REPORTED("reported"),
    TRIAGED("triaged"),
    ASSIGNED("assigned"),
    EN_ROUTE("en_route"),
    ON_SCENE("on_scene"),
    RESOLVED("resolved"),
    CANCELLED("cancelled");

    private final String wireValue;

    IncidentStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The string used in JSON and stored in the database. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static IncidentStatus fromWireValue(String value) {
        for (IncidentStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown incident status: " + value);
    }
}
