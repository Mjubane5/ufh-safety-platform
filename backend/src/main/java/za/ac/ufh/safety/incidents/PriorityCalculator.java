package za.ac.ufh.safety.incidents;

import org.springframework.stereotype.Component;

/**
 * Works out incident priority, 1 to 5, where 1 is most urgent.
 *
 * The contract says priority is computed by the backend and that the client
 * never sets it and never assumes the formula. This is the "Java equivalent"
 * the contract allows; the C++ module can replace it later without the
 * frontend noticing, because the number is all that crosses the wire.
 *
 * The ranking is by risk to life:
 *   1  sos       - someone pressed the panic button
 *   2  fire, medical, assault - immediate danger to a person
 *   3  accident  - likely injury, usually already being dealt with
 *   4  theft, suspicious - property or a warning sign, no injury reported
 *   5  unsafe, other - environmental or unclassified, triage by hand
 *
 * A missing location does not change priority. An SOS with no coordinates is
 * still an SOS; the contract handles the location problem separately with
 * locationSource and manual triage.
 */
@Component
public class PriorityCalculator {

    public int calculate(IncidentType type) {
        return switch (type) {
            case SOS -> 1;
            case FIRE, MEDICAL, ASSAULT -> 2;
            case ACCIDENT -> 3;
            case THEFT, SUSPICIOUS -> 4;
            case UNSAFE, OTHER -> 5;
        };
    }
}
