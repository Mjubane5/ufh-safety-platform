package za.ac.ufh.safety.wellness;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores WellnessBookingStatus as its contract string. See IncidentStatusConverter. */
@Converter(autoApply = true)
public class WellnessBookingStatusConverter implements AttributeConverter<WellnessBookingStatus, String> {

    @Override
    public String convertToDatabaseColumn(WellnessBookingStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public WellnessBookingStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : WellnessBookingStatus.fromWireValue(dbValue);
    }
}
