package org.arghyam.jalsoochak.tenant.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GenerateApiTokenResponseDTO {

    @Schema(description = "Raw API token — shown exactly once. Store it securely; it cannot be retrieved again.")
    @JsonProperty("token")
    String token;
}
