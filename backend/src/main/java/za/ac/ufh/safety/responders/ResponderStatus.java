package za.ac.ufh.safety.responders;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Responder availability, from docs/api-contract.md section 4.
 *
 *   available  - on duty and not on a call, so eligible for assignment
 *   assigned   - on duty and already on a call
 *   off_duty   - not working, never eligible
 *
 * Same wireValue pattern as IncidentStatus: the enum name is for Java, the
 * wire value is what goes in JSON and in the database column.
 */
public enum ResponderStatus {

    AVAILABLE("available"),
    ASSIGNED("assigned"),
    OFF_DUTY("off_duty");

    private final String wireValue;

    ResponderStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ResponderStatus fromWireValue(String value) {
        for (ResponderStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown responder status: " + value);
    }
}
