package za.ac.ufh.safety.gbv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A GBV report's lifecycle, from docs/api-contract.md section 7:
 * submitted -> under_review -> referred or closed.
 */
public enum GbvReportStatus {

    SUBMITTED("submitted"),
    UNDER_REVIEW("under_review"),
    REFERRED("referred"),
    CLOSED("closed");

    private final String wireValue;

    GbvReportStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static GbvReportStatus fromWireValue(String value) {
        for (GbvReportStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown GBV report status: " + value);
    }
}
