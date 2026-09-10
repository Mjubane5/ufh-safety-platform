package za.ac.ufh.safety.incidents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The incident types listed in docs/api-contract.md section 3.
 *
 * Java constants are UPPER_CASE by convention but the contract sends
 * lowercase strings, so each constant carries the exact string that goes on
 * the wire. Nothing derives the wire value from the constant name — if the
 * two ever need to differ, they can.
 */
public enum IncidentType {

    SOS("sos"),
    MEDICAL("medical"),
    FIRE("fire"),
    THEFT("theft"),
    ASSAULT("assault"),
    ACCIDENT("accident"),
    SUSPICIOUS("suspicious"),
    UNSAFE("unsafe"),
    OTHER("other");

    private final String wireValue;

    IncidentType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The string used in JSON and stored in the database. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static IncidentType fromWireValue(String value) {
        for (IncidentType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown incident type: " + value);
    }
}
