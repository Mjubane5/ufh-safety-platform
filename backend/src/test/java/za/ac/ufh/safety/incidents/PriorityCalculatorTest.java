package za.ac.ufh.safety.incidents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityCalculatorTest {

    private final PriorityCalculator calculator = new PriorityCalculator();

    @Test
    void sosIsTheMostUrgent() {
        assertThat(calculator.calculate(IncidentType.SOS)).isEqualTo(1);
    }

    @Test
    void lifeThreateningTypesRankAbovePropertyCrime() {
        assertThat(calculator.calculate(IncidentType.MEDICAL))
            .isLessThan(calculator.calculate(IncidentType.THEFT));
        assertThat(calculator.calculate(IncidentType.FIRE))
            .isLessThan(calculator.calculate(IncidentType.SUSPICIOUS));
    }

    // The contract fixes the range at 1..5. If someone adds a type and forgets
    // the switch, this fails rather than letting a 0 reach the dashboard.
    @Test
    void everyTypeGetsAPriorityInRange() {
        for (IncidentType type : IncidentType.values()) {
            assertThat(calculator.calculate(type))
                .as("priority for %s", type)
                .isBetween(1, 5);
        }
    }
}
