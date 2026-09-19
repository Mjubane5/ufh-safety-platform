package za.ac.ufh.safety.safetywalk;

import java.time.Instant;

public record HotspotResponse(
        Long hotspotId,
        String name,
        Double latitude,
        Double longitude,
        Double radiusMetres,
        String riskLevel,
        Integer incidentCount,
        Instant computedAt
) {
}