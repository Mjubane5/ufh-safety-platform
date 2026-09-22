package za.ac.ufh.safety.wellness;

/** GET /api/wellness/resources item shape. See WellnessService.RESOURCES. */
public record WellnessResourceItem(
        Long resourceId,
        String title,
        String category,
        String description,
        String contactPhone,
        String availability
) {
}
