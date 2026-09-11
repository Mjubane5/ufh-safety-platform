package za.ac.ufh.safety.responders;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks which responder to send when the dispatcher does not name one.
 *
 * ---------------------------------------------------------------------------
 * What this does, from docs/api-contract.md section 4
 * ---------------------------------------------------------------------------
 *
 * 1. Only a responder who is AVAILABLE and has a known position can be
 *    chosen. Responder.isLocatable() is that test.
 *
 * 2. Of those, choose the closest to the incident.
 *
 * 3. If none qualify, return empty. The caller turns that into 404 "no
 *    responder available".
 *
 * The hard rule, and the one worth being able to explain out loud:
 *
 *    An incident with locationSource "none" must never reach this class.
 *    With no incident position there is nothing to measure distance from, so
 *    "nearest" has no meaning. Picking anyway would be choosing at random
 *    and labelling the result "nearest available responder" — help goes to
 *    the wrong place while the screen looks correct. The assign endpoint
 *    returns 409 for that case instead, and a dispatcher who has read the
 *    description assigns by hand.
 *
 * ---------------------------------------------------------------------------
 * Implementation notes
 * ---------------------------------------------------------------------------
 *
 * Straight-line distance is enough here. Campus is roughly two kilometres
 * across, so the error from treating latitude/longitude as a flat plane is
 * metres — far smaller than a phone's GPS accuracy, which the incident
 * records in its `accuracy` field. Do not reach for a geospatial library.
 *
 * Remember longitude degrees get narrower away from the equator. At Alice
 * (roughly 32.8 degrees south) a degree of longitude is about cos(32.8) =
 * 0.84 of a degree of latitude. Comparing raw degree differences without
 * that factor stretches the map east-west and will pick the wrong responder
 * when two are at similar distances on different bearings.
 *
 * Comparing squared distances is fine — whichever is nearest by the square
 * is nearest by the root, and it avoids a Math.sqrt per candidate.
 *
 * The real shortest-path work over the campus footpath graph is the C++
 * module. This class only chooses *who*; the route between them comes later.
 *
 * ---------------------------------------------------------------------------
 * The specification is NearestResponderSelectorTest, written before this code.
 * ---------------------------------------------------------------------------
 */
public final class NearestResponderSelector {

    private NearestResponderSelector() {}

    /**
     * @param candidates    every responder to consider, in any state
     * @param latitude      the incident's latitude, never null
     * @param longitude     the incident's longitude, never null
     * @return the responder to send, or empty when nobody qualifies
     */
    public static Optional<Responder> choose(List<Responder> candidates,
                                             double latitude,
                                             double longitude) {
        return candidates.stream()
            .filter(Responder::isLocatable)
            .min(Comparator.comparingDouble(
                candidate -> distanceSquared(candidate, latitude, longitude)));
    }

    /**
     * Squared distance from a responder to a point, in latitude-degrees.
     *
     * Squared, not the true distance: whichever candidate is nearest by the
     * square is nearest by the root, so the Math.sqrt per candidate buys
     * nothing. The value is only ever compared, never shown to anyone.
     *
     * The longitude difference is scaled by cos(latitude) because degrees of
     * longitude converge towards the poles. At Alice, roughly 32.8 degrees
     * south, one degree of longitude covers about 0.84 of the ground one
     * degree of latitude does. Skip the scaling and the map is stretched
     * east-west, which picks the wrong responder whenever two are at similar
     * distances on different bearings.
     *
     * Only safe to call on a responder that isLocatable() — the coordinates
     * are unboxed here and a null would throw.
     */
    private static double distanceSquared(Responder responder,
                                          double latitude,
                                          double longitude) {
        double longitudeScale = Math.cos(Math.toRadians(latitude));

        double deltaLatitude = responder.getLatitude() - latitude;
        double deltaLongitude = (responder.getLongitude() - longitude) * longitudeScale;

        return deltaLatitude * deltaLatitude + deltaLongitude * deltaLongitude;
    }
}
