package za.ac.ufh.safety.incidents;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores IncidentType as its contract string.
 *
 * The plain JPA option, @Enumerated(EnumType.STRING), would write the Java
 * constant name instead — "EN_ROUTE" rather than "en_route" — so the database
 * would disagree with docs/api-contract.md. autoApply means every
 * IncidentType field is converted without annotating each one.
 */
@Converter(autoApply = true)
public class IncidentTypeConverter implements AttributeConverter<IncidentType, String> {

    @Override
    public String convertToDatabaseColumn(IncidentType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public IncidentType convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : IncidentType.fromWireValue(dbValue);
    }
}
