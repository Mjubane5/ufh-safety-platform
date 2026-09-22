package za.ac.ufh.safety.safetywalk;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class SafetyWalkService {

    private static final double EARTH_RADIUS_METRES = 6371000.0;
    private static final double DEFAULT_RADIUS_METRES = 500.0;

    private final PatrolRepository patrols;
    private final UserRepository users;

    public SafetyWalkService(PatrolRepository patrols,
                             UserRepository users) {
        this.patrols = patrols;
        this.users = users;
    }

    @Transactional
    public PatrolCreatedResponse createPatrol(
            String email,
            PatrolCreateRequest request) {

        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(
                        401,
                        "NOT_AUTHENTICATED",
                        "Your account could not be found.",
                        null
                ));

        if (!"campus_control".equals(user.getRole())) {
            throw new ApiException(
                    403,
                    "FORBIDDEN",
                    "Only a campus control account can record a patrol.",
                    null
            );
        }

        Patrol patrol = new Patrol();

        patrol.setZoneId(request.zoneId());
        patrol.setZoneName(zoneName(request.zoneId()));
        patrol.setLatitude(request.latitude());
        patrol.setLongitude(request.longitude());
        patrol.setNote(trimToNull(request.note()));
        patrol.setRecordedByUserId(user.getUserId());

        Patrol saved = patrols.save(patrol);

        return new PatrolCreatedResponse(
                saved.getPatrolId(),
                saved.getZoneId(),
                saved.getRecordedAt()
        );
    }

    @Transactional(readOnly = true)
    public PatrolRecentResponse getRecentPatrols(
            Double latitude,
            Double longitude,
            Double radiusMetres) {

        if (latitude == null || longitude == null) {
            throw new ApiException(
                    400,
                    "VALIDATION_FAILED",
                    "latitude and longitude are required.",
                    latitude == null ? "latitude" : "longitude"
            );
        }

        if (latitude < -90 || latitude > 90) {
            throw new ApiException(
                    400,
                    "VALIDATION_FAILED",
                    "latitude must be between -90 and 90.",
                    "latitude"
            );
        }

        if (longitude < -180 || longitude > 180) {
            throw new ApiException(
                    400,
                    "VALIDATION_FAILED",
                    "longitude must be between -180 and 180.",
                    "longitude"
            );
        }

        double radius = radiusMetres == null
                ? DEFAULT_RADIUS_METRES
                : radiusMetres;

        if (radius <= 0 || radius > 50000) {
            throw new ApiException(
                    400,
                    "VALIDATION_FAILED",
                    "radiusMetres must be between 1 and 50000.",
                    "radiusMetres"
            );
        }

        Instant now = Instant.now();

        List<PatrolRecentItem> items = patrols.findAll()
                .stream()
                .filter(patrol ->
                        distanceMetres(
                                latitude,
                                longitude,
                                patrol.getLatitude(),
                                patrol.getLongitude()
                        ) <= radius
                )
                .sorted(
                        Comparator.comparing(
                                Patrol::getRecordedAt
                        ).reversed()
                )
                .map(patrol -> new PatrolRecentItem(
                        patrol.getPatrolId(),
                        patrol.getZoneName(),
                        patrol.getLatitude(),
                        patrol.getLongitude(),
                        patrol.getRecordedAt(),
                        Math.max(
                                0,
                                Duration.between(
                                        patrol.getRecordedAt(),
                                        now
                                ).toMinutes()
                        )
                ))
                .toList();

        return new PatrolRecentResponse(items);
    }

    private static String zoneName(Long zoneId) {

        Map<Long, String> names = Map.of(
                1L, "Main Gate",
                2L, "Student Residence",
                3L, "Library Precinct",
                4L, "Sports Grounds Gate",
                5L, "East Gate Perimeter"
        );

        return names.getOrDefault(
                zoneId,
                "Zone " + zoneId
        );
    }

    public static double distanceMetres(
            double lat1,
            double lon1,
            double lat2,
            double lon2) {

        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        +
                        Math.cos(lat1Rad)
                                * Math.cos(lat2Rad)
                                * Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);

        return EARTH_RADIUS_METRES
                * 2
                * Math.atan2(
                Math.sqrt(a),
                Math.sqrt(1 - a)
        );
    }

    private static String trimToNull(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty()
                ? null
                : trimmed;
    }
}