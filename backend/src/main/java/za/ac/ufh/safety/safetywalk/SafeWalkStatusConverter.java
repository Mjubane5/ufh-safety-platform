package za.ac.ufh.safety.safetywalk;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores SafeWalkStatus as its contract string. See IncidentStatusConverter. */
@Converter(autoApply = true)
public class SafeWalkStatusConverter implements AttributeConverter<SafeWalkStatus, String> {

    @Override
    public String convertToDatabaseColumn(SafeWalkStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SafeWalkStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : SafeWalkStatus.fromWireValue(dbValue);
    }
}
