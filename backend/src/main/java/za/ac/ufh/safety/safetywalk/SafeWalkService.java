package za.ac.ufh.safety.safetywalk;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

@Service
public class SafeWalkService {

    private final SafeWalkRepository safeWalks;
    private final UserRepository users;

    public SafeWalkService(SafeWalkRepository safeWalks, UserRepository users) {
        this.safeWalks = safeWalks;
        this.users = users;
    }

    @Transactional
    public SafeWalkResponse start(String email, StartSafeWalkRequest request) {
        User student = requireStudent(email);

        SafeWalk walk = new SafeWalk();
        walk.setStudentUserId(student.getUserId());
        walk.setOriginLatitude(request.origin().latitude());
        walk.setOriginLongitude(request.origin().longitude());
        walk.setDestinationLatitude(request.destination().latitude());
        walk.setDestinationLongitude(request.destination().longitude());
        walk.setCurrentLatitude(request.origin().latitude());
        walk.setCurrentLongitude(request.origin().longitude());
        walk.setSafetyScore(request.route() == null ? null : request.route().safetyScore());
        walk.setStatus(SafeWalkStatus.ACTIVE);

        return SafeWalkResponse.of(safeWalks.save(walk));
    }

    @Transactional
    public SafeWalkResponse updateLocation(String email, Long walkId, UpdateSafeWalkLocationRequest request) {
        SafeWalk walk = requireOwnActiveWalk(email, walkId);
        walk.setCurrentLatitude(request.latitude());
        walk.setCurrentLongitude(request.longitude());
        return SafeWalkResponse.of(safeWalks.save(walk));
    }

    @Transactional
    public SafeWalkResponse markArrived(String email, Long walkId) {
        SafeWalk walk = requireOwnActiveWalk(email, walkId);
        walk.setStatus(SafeWalkStatus.ARRIVED);
        return SafeWalkResponse.of(safeWalks.save(walk));
    }

    @Transactional
    public SafeWalkResponse cancel(String email, Long walkId) {
        SafeWalk walk = requireOwnActiveWalk(email, walkId);
        walk.setStatus(SafeWalkStatus.CANCELLED);
        return SafeWalkResponse.of(safeWalks.save(walk));
    }

    @Transactional(readOnly = true)
    public ActiveSafeWalksResponse listActive(String email) {
        User caller = requireUser(email);
        if (!"campus_control".equals(caller.getRole()) && !"admin".equals(caller.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Your role cannot view active Safe Walks.", null);
        }

        List<ActiveSafeWalksResponse.Item> items = safeWalks.findByStatus(SafeWalkStatus.ACTIVE).stream()
            .sorted(Comparator.comparing(SafeWalk::getUpdatedAt).reversed())
            .map(walk -> {
                User student = users.findById(walk.getStudentUserId()).orElse(null);
                return new ActiveSafeWalksResponse.Item(
                    walk.getWalkId(),
                    walk.getStudentUserId(),
                    student == null ? null : student.getFullName(),
                    new ActiveSafeWalksResponse.Coordinate(walk.getDestinationLatitude(), walk.getDestinationLongitude()),
                    new ActiveSafeWalksResponse.Coordinate(walk.getCurrentLatitude(), walk.getCurrentLongitude()),
                    walk.getSafetyScore(),
                    walk.getStartedAt(),
                    walk.getUpdatedAt());
            })
            .toList();

        return new ActiveSafeWalksResponse(items);
    }

    /**
     * Own-walk-only and must still be active. A caller who owns no such walk
     * gets 404, not 403 - same reasoning as IncidentService.requireCanView:
     * 403 would confirm a walk with that id exists at all.
     *
     * The contract only documents 409 explicitly for the location PATCH, but
     * the same guard applies to arrived/cancel too: neither makes sense to
     * call on a walk that already ended one way or the other.
     */
    private SafeWalk requireOwnActiveWalk(String email, Long walkId) {
        User caller = requireUser(email);
        SafeWalk walk = safeWalks.findById(walkId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such Safe Walk.", null));

        if (!caller.getUserId().equals(walk.getStudentUserId())) {
            throw new ApiException(404, "NOT_FOUND", "No such Safe Walk.", null);
        }
        if (walk.getStatus() != SafeWalkStatus.ACTIVE) {
            throw new ApiException(409, "CONFLICT",
                "This Safe Walk is already " + walk.getStatus().wireValue() + ".", null);
        }
        return walk;
    }

    private User requireStudent(String email) {
        User user = requireUser(email);
        if (!"student".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Only a student account can start a Safe Walk.", null);
        }
        return user;
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
    }
}
