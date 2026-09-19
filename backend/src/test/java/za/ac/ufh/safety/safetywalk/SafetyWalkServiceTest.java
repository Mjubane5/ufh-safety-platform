package za.ac.ufh.safety.safetywalk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafetyWalkServiceTest {

    private PatrolRepository patrols;
    private UserRepository users;
    private SafetyWalkService service;

    @BeforeEach
    void setUp() {
        patrols = mock(PatrolRepository.class);
        users = mock(UserRepository.class);
        service = new SafetyWalkService(patrols, users);
    }

    private User user(String email, String role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 9L);
        user.setEmail(email);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        return user;
    }

    private Patrol savedPatrol(Long patrolId, Long zoneId, String zoneName, Instant recordedAt) {
        Patrol patrol = new Patrol();
        ReflectionTestUtils.setField(patrol, "patrolId", patrolId);
        patrol.setZoneId(zoneId);
        patrol.setZoneName(zoneName);
        patrol.setLatitude(-32.78400);
        patrol.setLongitude(26.85010);
        patrol.setRecordedByUserId(1L);
        ReflectionTestUtils.setField(patrol, "recordedAt", recordedAt);
        return patrol;
    }

    // --- createPatrol -------------------------------------------------

    @Test
    void campusControlCanRecordAPatrol() {
        user("control@ufh.ac.za", "campus_control");
        when(patrols.save(any())).thenAnswer(inv -> {
            Patrol p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "patrolId", 88L);
            ReflectionTestUtils.setField(p, "recordedAt", Instant.parse("2026-09-19T19:00:00Z"));
            return p;
        });

        PatrolCreatedResponse response = service.createPatrol(
                "control@ufh.ac.za",
                new PatrolCreateRequest(3L, -32.78400, 26.85010, "Routine sweep"));

        assertThat(response.patrolId()).isEqualTo(88L);
        assertThat(response.zoneId()).isEqualTo(3L);
        assertThat(response.recordedAt()).isEqualTo(Instant.parse("2026-09-19T19:00:00Z"));
    }

    // Every other role, not just students, must be refused. A responder or a
    // GBV officer recording a "patrol" is exactly as wrong as a student doing it.
    @Test
    void aStudentCannotRecordAPatrol() {
        user("student@ufh.ac.za", "student");

        assertThatThrownBy(() -> service.createPatrol(
                "student@ufh.ac.za",
                new PatrolCreateRequest(1L, -32.78400, 26.85010, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a campus control account");
    }

    @Test
    void aResponderCannotRecordAPatrol() {
        user("responder@ufh.ac.za", "responder");

        assertThatThrownBy(() -> service.createPatrol(
                "responder@ufh.ac.za",
                new PatrolCreateRequest(1L, -32.78400, 26.85010, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a campus control account");
    }

    @Test
    void anUnrecognisedCallerIsNotAuthenticatedRatherThanForbidden() {
        when(users.findByEmailIgnoreCase("ghost@ufh.ac.za")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPatrol(
                "ghost@ufh.ac.za",
                new PatrolCreateRequest(1L, -32.78400, 26.85010, null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 401);
    }

    // recordedByUserId is set so the row can be audited later, but nothing in
    // the response or the /recent listing may repeat it back. The DTOs enforce
    // this structurally; this test pins the entity write itself.
    @Test
    void theRecordingOfficerIsStoredOnTheEntityForAuditingOnly() {
        User control = user("control@ufh.ac.za", "campus_control");
        var captor = forClass(Patrol.class);
        when(patrols.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.createPatrol("control@ufh.ac.za",
                new PatrolCreateRequest(3L, -32.78400, 26.85010, null));

        assertThat(captor.getValue().getRecordedByUserId()).isEqualTo(control.getUserId());
    }

    @Test
    void aKnownZoneIdGetsItsRealName() {
        user("control@ufh.ac.za", "campus_control");
        var captor = forClass(Patrol.class);
        when(patrols.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.createPatrol("control@ufh.ac.za",
                new PatrolCreateRequest(3L, -32.78400, 26.85010, null));

        assertThat(captor.getValue().getZoneName()).isEqualTo("Library Precinct");
    }

    // Nothing in the contract defines a fixed zone list, so a zoneId outside
    // the known five is accepted rather than rejected - it just gets a
    // generic name instead of a real one. Documented here as current
    // behaviour, not necessarily final behaviour.
    @Test
    void anUnrecognisedZoneIdGetsAGenericNameRatherThanBeingRejected() {
        user("control@ufh.ac.za", "campus_control");
        var captor = forClass(Patrol.class);
        when(patrols.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.createPatrol("control@ufh.ac.za",
                new PatrolCreateRequest(999L, -32.78400, 26.85010, null));

        assertThat(captor.getValue().getZoneName()).isEqualTo("Zone 999");
    }

    @Test
    void aBlankNoteIsStoredAsNullNotAnEmptyString() {
        user("control@ufh.ac.za", "campus_control");
        var captor = forClass(Patrol.class);
        when(patrols.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.createPatrol("control@ufh.ac.za",
                new PatrolCreateRequest(3L, -32.78400, 26.85010, "   "));

        assertThat(captor.getValue().getNote()).isNull();
    }

    // --- getRecentPatrols -----------------------------------------------

    @Test
    void missingLatitudeIsRejectedWithTheFieldNamed() {
        assertThatThrownBy(() -> service.getRecentPatrols(null, 26.85010, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("field", "latitude");
    }

    @Test
    void missingLongitudeIsRejectedWithTheFieldNamed() {
        assertThatThrownBy(() -> service.getRecentPatrols(-32.78400, null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("field", "longitude");
    }

    @Test
    void latitudeOutOfRangeIsRejected() {
        assertThatThrownBy(() -> service.getRecentPatrols(91.0, 26.85010, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("field", "latitude");
    }

    @Test
    void longitudeOutOfRangeIsRejected() {
        assertThatThrownBy(() -> service.getRecentPatrols(-32.78400, 181.0, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("field", "longitude");
    }

    @Test
    void aNonPositiveRadiusIsRejected() {
        assertThatThrownBy(() -> service.getRecentPatrols(-32.78400, 26.85010, 0.0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("field", "radiusMetres");
    }

    @Test
    void aRadiusOverFiftyKilometresIsRejected() {
        assertThatThrownBy(() -> service.getRecentPatrols(-32.78400, 26.85010, 50001.0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("field", "radiusMetres");
    }

    @Test
    void withoutARadiusTheDefaultFiveHundredMetresApplies() {
        // ~450m away, so it should be included under the default 500m radius
        // but would be excluded by a tighter one used elsewhere in this file.
        when(patrols.findAll()).thenReturn(List.of(
                savedPatrol(1L, 3L, "Library Precinct", Instant.now().minusSeconds(60))));

        PatrolRecentResponse response = service.getRecentPatrols(-32.78800, 26.85010, null);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void aPatrolOutsideTheRequestedRadiusIsExcluded() {
        when(patrols.findAll()).thenReturn(List.of(
                savedPatrol(1L, 3L, "Library Precinct", Instant.now())));

        // The stored patrol is at -32.78400,26.85010; asking from a point
        // roughly 5km away with only a 100m radius must exclude it.
        PatrolRecentResponse response = service.getRecentPatrols(-32.83000, 26.85010, 100.0);

        assertThat(response.items()).isEmpty();
    }

    @Test
    void recentPatrolsComeBackMostRecentFirst() {
        when(patrols.findAll()).thenReturn(List.of(
                savedPatrol(1L, 3L, "Library Precinct", Instant.now().minusSeconds(600)),
                savedPatrol(2L, 1L, "Main Gate", Instant.now().minusSeconds(30))));

        PatrolRecentResponse response = service.getRecentPatrols(-32.78400, 26.85010, 5000.0);

        assertThat(response.items()).extracting(PatrolRecentItem::patrolId)
                .containsExactly(2L, 1L);
    }

    @Test
    void minutesAgoIsComputedFromRecordedAtNotSuppliedByTheCaller() {
        when(patrols.findAll()).thenReturn(List.of(
                savedPatrol(1L, 3L, "Library Precinct", Instant.now().minusSeconds(5 * 60))));

        PatrolRecentResponse response = service.getRecentPatrols(-32.78400, 26.85010, 5000.0);

        assertThat(response.items().get(0).minutesAgo()).isEqualTo(5L);
    }

    // The response DTO has no field for it, so this is really documenting the
    // contract's "never expose patrol officer identity" rule at the type level.
    @Test
    void theRecentListingNeverIncludesWhoRecordedIt() {
        when(patrols.findAll()).thenReturn(List.of(
                savedPatrol(1L, 3L, "Library Precinct", Instant.now())));

        PatrolRecentResponse response = service.getRecentPatrols(-32.78400, 26.85010, 5000.0);

        assertThat(PatrolRecentItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("patrolId", "zoneName", "latitude", "longitude", "recordedAt", "minutesAgo");
    }

    // --- distanceMetres ---------------------------------------------------

    @Test
    void distanceBetweenAPointAndItselfIsZero() {
        assertThat(SafetyWalkService.distanceMetres(-32.78400, 26.85010, -32.78400, 26.85010))
                .isEqualTo(0.0);
    }

    @Test
    void distanceMatchesTheKnownSeparationBetweenTwoCampusPoints() {
        // -32.78210,26.84800 to -32.79500,26.86000 is the same pair used as
        // seeded responder locations elsewhere; ~1821m apart by Haversine.
        double distance = SafetyWalkService.distanceMetres(
                -32.78210, 26.84800, -32.79500, 26.86000);

        assertThat(distance).isCloseTo(1821.0, offset(5.0));
    }
}
