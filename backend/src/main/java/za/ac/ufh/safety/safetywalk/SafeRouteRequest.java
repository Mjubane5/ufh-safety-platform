package za.ac.ufh.safety.safetywalk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record SafeRouteRequest(

        @NotNull
        @Valid
        LocationPoint from,

        @NotNull
        @Valid
        LocationPoint to
) {

        public record LocationPoint(

                @NotNull
                @DecimalMin("-90.0")
                @DecimalMax("90.0")
                Double latitude,

                @NotNull
                @DecimalMin("-180.0")
                @DecimalMax("180.0")
                Double longitude
        ) {
        }
}
