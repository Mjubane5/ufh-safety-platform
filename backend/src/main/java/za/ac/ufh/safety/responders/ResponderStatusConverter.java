package za.ac.ufh.safety.responders;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores ResponderStatus as its contract string. See IncidentStatusConverter. */
@Converter(autoApply = true)
public class ResponderStatusConverter implements AttributeConverter<ResponderStatus, String> {

    @Override
    public String convertToDatabaseColumn(ResponderStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public ResponderStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : ResponderStatus.fromWireValue(dbValue);
    }
}
