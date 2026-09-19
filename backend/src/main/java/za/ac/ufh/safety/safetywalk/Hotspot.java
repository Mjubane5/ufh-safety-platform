package za.ac.ufh.safety.safetywalk;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "hotspots")
public class Hotspot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hotspot_id")
    private Long hotspotId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "radius_metres", nullable = false)
    private Double radiusMetres;

    @Column(nullable = false, length = 20)
    private String riskLevel;

    @Column(name = "incident_count", nullable = false)
    private Integer incidentCount;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public Hotspot() {
    }

    public Long getHotspotId() {
        return hotspotId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Double getRadiusMetres() {
        return radiusMetres;
    }

    public void setRadiusMetres(Double radiusMetres) {
        this.radiusMetres = radiusMetres;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Integer getIncidentCount() {
        return incidentCount;
    }

    public void setIncidentCount(Integer incidentCount) {
        this.incidentCount = incidentCount;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}