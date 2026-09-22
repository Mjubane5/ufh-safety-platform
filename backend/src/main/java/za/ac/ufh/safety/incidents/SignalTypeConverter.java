package za.ac.ufh.safety.incidents;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores SignalType as its contract string. See IncidentStatusConverter. */
@Converter(autoApply = true)
public class SignalTypeConverter implements AttributeConverter<SignalType, String> {

    @Override
    public String convertToDatabaseColumn(SignalType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SignalType convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : SignalType.fromWireValue(dbValue);
    }
}
