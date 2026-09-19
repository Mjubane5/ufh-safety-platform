package za.ac.ufh.safety.safetywalk;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HotspotService {

    private final HotspotRepository hotspots;

    public HotspotService(HotspotRepository hotspots) {
        this.hotspots = hotspots;
    }

    public List<HotspotResponse> getHotspots() {

        return hotspots.findAll()
                .stream()
                .map(hotspot -> new HotspotResponse(
                        hotspot.getHotspotId(),
                        hotspot.getName(),
                        hotspot.getLatitude(),
                        hotspot.getLongitude(),
                        hotspot.getRadiusMetres(),
                        hotspot.getRiskLevel(),
                        hotspot.getIncidentCount(),
                        hotspot.getComputedAt()
                ))
                .toList();
    }
}