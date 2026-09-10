package za.ac.ufh.safety.incidents;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores IncidentStatus as its contract string. See IncidentTypeConverter. */
@Converter(autoApply = true)
public class IncidentStatusConverter implements AttributeConverter<IncidentStatus, String> {

    @Override
    public String convertToDatabaseColumn(IncidentStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public IncidentStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : IncidentStatus.fromWireValue(dbValue);
    }
}
