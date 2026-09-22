package za.ac.ufh.safety.safetywalk;

import java.util.Map;

/**
 * riskLevel -> how heavily it counts, shared by everything that reasons
 * about hotspot danger: SafeRouteService's safetyScore, GeometricRouteEngine's
 * choice of which hotspot to detour around, and CppRouteEngine's per-edge
 * risk penalty passed to the shortest-path search. One definition, three
 * consumers - matches the four levels docs/api-contract.md defines for a
 * hotspot's riskLevel.
 */
final class RiskWeights {

    private static final Map<String, Double> WEIGHTS = Map.of(
            "low", 0.15,
            "moderate", 0.35,
            "elevated", 0.6,
            "high", 0.9
    );
    // An unrecognised riskLevel is treated as moderate-ish rather than ignored.
    private static final double DEFAULT_WEIGHT = 0.5;

    private RiskWeights() {}

    static double of(String riskLevel) {
        return WEIGHTS.getOrDefault(riskLevel, DEFAULT_WEIGHT);
    }
}
