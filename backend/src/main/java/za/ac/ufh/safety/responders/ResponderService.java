package za.ac.ufh.safety.responders;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

@Service
public class ResponderService {

    private final ResponderRepository responders;
    private final UserRepository users;

    public ResponderService(ResponderRepository responders, UserRepository users) {
        this.responders = responders;
        this.users = users;
    }

    /**
     * GET /api/responders/available — the list a dispatcher picks from.
     *
     * Campus control and admin only. A student must not be able to read where
     * every responder on campus currently is.
     */
    @Transactional(readOnly = true)
    public ResponderListResponse available(String email) {
        requireDispatcher(email);

        List<Responder> free = responders.findByStatus(ResponderStatus.AVAILABLE);
        if (free.isEmpty()) {
            return new ResponderListResponse(List.of());
        }

        // One query for all the names instead of one per responder. With a
        // dozen responders the difference is invisible; the habit is what
        // stops a list endpoint getting slow later.
        Map<Long, User> byId = users.findAllById(
                free.stream().map(Responder::getResponderId).toList())
            .stream()
            .collect(Collectors.toMap(User::getUserId, Function.identity()));

        List<ResponderSummary> items = free.stream()
            .map(responder -> ResponderSummary.of(responder, byId.get(responder.getResponderId())))
            .sorted(Comparator.comparing(ResponderSummary::fullName,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        return new ResponderListResponse(items);
    }

    private User requireDispatcher(String email) {
        User caller = users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));

        return switch (caller.getRole()) {
            case "campus_control", "admin" -> caller;
            default -> throw new ApiException(403, "FORBIDDEN",
                "Only campus control can see responder availability.", null);
        };
    }
}
