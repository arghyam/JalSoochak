package org.arghyam.jalsoochak.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response payload for a generated (or cached) report.
 * The {@code downloadUrl} is a short-lived presigned URL — it is never
 * persisted and must be used before {@code urlExpiresAt}.
 */
@Builder
public record ReportResponseDTO(
        @Schema(description = "Stable id of the cached report row")
        UUID reportId,

        @Schema(description = "File format", example = "CSV")
        String format,

        @Schema(description = "When the underlying file was first generated")
        OffsetDateTime generatedAt,

        @Schema(description = "Data version snapshot used to key the cache", example = "42")
        long dataVersion,

        @Schema(description = "Short-lived presigned URL — fetch before urlExpiresAt")
        String downloadUrl,

        @Schema(description = "Absolute expiry timestamp for downloadUrl")
        OffsetDateTime urlExpiresAt,

        @Schema(description = "True when the file was served from cache (no regeneration)")
        boolean cached
) {
}
