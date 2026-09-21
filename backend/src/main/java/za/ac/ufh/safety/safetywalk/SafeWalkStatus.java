package za.ac.ufh.safety.safetywalk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A Safe Walk session's lifecycle, from docs/api-contract.md's "Safe Walk
 * sessions" section: active -> arrived, or active -> cancelled. Unlike an
 * incident there is no in-between state - a walk is either happening, or it
 * is finished one way or the other.
 */
public enum SafeWalkStatus {

    ACTIVE("active"),
    ARRIVED("arrived"),
    CANCELLED("cancelled");

    private final String wireValue;

    SafeWalkStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static SafeWalkStatus fromWireValue(String value) {
        for (SafeWalkStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown safe walk status: " + value);
    }
}
