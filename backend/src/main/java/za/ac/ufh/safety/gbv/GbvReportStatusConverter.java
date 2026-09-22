package za.ac.ufh.safety.gbv;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores GbvReportStatus as its contract string. See IncidentStatusConverter. */
@Converter(autoApply = true)
public class GbvReportStatusConverter implements AttributeConverter<GbvReportStatus, String> {

    @Override
    public String convertToDatabaseColumn(GbvReportStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public GbvReportStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : GbvReportStatus.fromWireValue(dbValue);
    }
}
