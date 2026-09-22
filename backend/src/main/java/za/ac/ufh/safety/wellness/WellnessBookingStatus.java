package za.ac.ufh.safety.wellness;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A wellness booking's lifecycle, from docs/api-contract.md's wellness
 * section: requested -> confirmed or declined, or cancelled from any state.
 * Same wireValue pattern as IncidentStatus/ResponderStatus.
 */
public enum WellnessBookingStatus {

    REQUESTED("requested"),
    CONFIRMED("confirmed"),
    DECLINED("declined"),
    CANCELLED("cancelled");

    private final String wireValue;

    WellnessBookingStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static WellnessBookingStatus fromWireValue(String value) {
        for (WellnessBookingStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown wellness booking status: " + value);
    }
}
