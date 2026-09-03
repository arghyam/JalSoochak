package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocationReadingRequest {

    @JsonAlias({"organization_id", "organizationId"})
    private Integer organizationId;

    @NotNull
    @JsonAlias({"lat", "latitude"})
    private BigDecimal latitude;

    @NotNull
    @JsonAlias({"lng", "lon", "long", "longitude"})
    private BigDecimal longitude;

    @Valid
    @NotNull
    private Contact contact;

    @JsonIgnore
    public String resolveContactId() {
        return contact != null ? contact.phone : null;
    }

    /**
     * Glific posts the full contact object, but only {@code phone} is used — it resolves the
     * operator. The WhatsApp profile name it also sends is deliberately not bound: nothing reads it,
     * and an unbound field cannot become a sink for whatever a caller puts there. {@code
     * ignoreUnknown} keeps those extra keys from failing the request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contact {
        @NotBlank
        private String phone;

        private Long id;
    }
}
