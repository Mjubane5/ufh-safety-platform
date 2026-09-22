package za.ac.ufh.safety.gbv;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Limits GET /api/gbv/reports/{code}/status per caller IP, so the endpoint
 * cannot be used to brute-force reference codes by trying many at speed -
 * limiting per-code alone would not stop that, since the attack is trying
 * many different codes, not repeating one.
 *
 * In-memory and per-instance: correct for this project's single-instance
 * deployment (see README-BACKEND.md), but a multi-instance deployment would
 * need a shared store (Redis, or a database table) so limits are not reset
 * by hitting a different instance - documented here as a known limitation,
 * not an oversight.
 */
@Component
public class GbvStatusLookupRateLimiter {

    private static final int MAX_REQUESTS_PER_WINDOW = 10;
    private static final long WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

    /** True if this IP may make another request right now; also records the attempt if so. */
    public boolean allow(String clientIp) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(WINDOW_SECONDS);

        Deque<Instant> attempts = requestsByIp.computeIfAbsent(clientIp, ip -> new ConcurrentLinkedDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= MAX_REQUESTS_PER_WINDOW) {
                return false;
            }
            attempts.addLast(now);
            return true;
        }
    }
}
