package org.arghyam.jalsoochak.analytics.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyStatusDto;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationResolutionStatusDto;
import org.arghyam.jalsoochak.analytics.dto.response.StatusItemDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics - Statuses", description = "Lookup lists for anomaly and escalation status filters")
@Slf4j
public class AnalyticsStatusController {

    @GetMapping("/anomalies/statuses")
    @Operation(
            summary = "List anomaly statuses",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Anomaly statuses fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.ANOMALY_STATUSES_SUCCESS)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE)
                            )
                    )
            }
    )
    public ResponseEntity<ApiResponse<List<StatusItemDto>>> getAnomalyStatuses() {
        try {
            List<StatusItemDto> statuses = Arrays.stream(AnomalyStatusDto.values())
                    .map(status -> new StatusItemDto(status.getCode(), status.getLabel()))
                    .toList();
            return ResponseEntity.ok(ApiResponse.<List<StatusItemDto>>builder()
                    .success(true)
                    .data(statuses)
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /api/v1/analytics/anomalies/statuses", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<List<StatusItemDto>>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }

    @GetMapping("/escalations/statuses")
    @Operation(
            summary = "List escalation statuses",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Escalation statuses fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.ESCALATION_STATUSES_SUCCESS)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE)
                            )
                    )
            }
    )
    public ResponseEntity<ApiResponse<List<StatusItemDto>>> getEscalationStatuses() {
        try {
            List<StatusItemDto> statuses = Arrays.stream(EscalationResolutionStatusDto.values())
                    .map(status -> new StatusItemDto(status.getCode(), status.getLabel()))
                    .toList();
            return ResponseEntity.ok(ApiResponse.<List<StatusItemDto>>builder()
                    .success(true)
                    .data(statuses)
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /api/v1/analytics/escalations/statuses", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<List<StatusItemDto>>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }
}
