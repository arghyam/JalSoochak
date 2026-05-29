package org.arghyam.jalsoochak.user.service.serviceImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.config.properties.StorageProperties;
import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.StorageException;
import org.arghyam.jalsoochak.user.repository.DataVersionRepository;
import org.arghyam.jalsoochak.user.repository.ReportsRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.StaffReportService;
import org.arghyam.jalsoochak.user.service.report.ReportDefinition;
import org.arghyam.jalsoochak.user.service.report.ReportWriter;
import org.arghyam.jalsoochak.user.service.report.StaffReportDefinition;
import org.arghyam.jalsoochak.user.storage.ObjectStorageService;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Thin per-resource orchestrator. Delegates resource-specific concerns
 * (schema, filter normalization, row fetching) to {@link StaffReportDefinition}
 * and format concerns to the injected {@link ReportWriter} beans, so adding
 * a new report type means writing one {@code ReportDefinition} and a thin
 * controller — no new generator classes per format.
 *
 * <p>Concurrent identical requests are tolerated by the
 * {@code INSERT … ON CONFLICT DO NOTHING} pattern in
 * {@link ReportsRepository#insertIfAbsent}: at most one duplicate upload
 * may happen and become orphaned in the bucket (cleaned up by bucket
 * lifecycle policy); the response is always a single canonical row.
 */
@Slf4j
@Service
public class StaffReportServiceImpl implements StaffReportService {

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final UserTenantRepository userTenantRepository;
    private final ReportsRepository reportsRepository;
    private final DataVersionRepository dataVersionRepository;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;
    private final ReportDefinition<TenantStaffResponseDTO, StaffReportRequestDTO> definition;
    private final Map<ReportFormat, ReportWriter> writers;
    private final ObjectMapper canonicalMapper;

    public StaffReportServiceImpl(
            UserTenantRepository userTenantRepository,
            ReportsRepository reportsRepository,
            DataVersionRepository dataVersionRepository,
            ObjectStorageService objectStorageService,
            StorageProperties storageProperties,
            StaffReportDefinition staffReportDefinition,
            List<ReportWriter> writerBeans
    ) {
        this.userTenantRepository = userTenantRepository;
        this.reportsRepository = reportsRepository;
        this.dataVersionRepository = dataVersionRepository;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
        this.definition = staffReportDefinition;
        this.writers = indexByFormat(writerBeans);
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Override
    public ReportResponseDTO generate(String tenantCode, ReportFormat format,
                                      StaffReportRequestDTO filters, Authentication caller) {
        Objects.requireNonNull(format, "format");
        String schema = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);

        StaffReportRequestDTO normalized = definition.normalize(filters);
        String paramsJson = toCanonicalJson(normalized);
        String paramsHash = sha256Hex(definition.type() + "|" + format.key() + "|" + paramsJson);
        long dataVersion = dataVersionRepository.getCurrent(schema, definition.resourceType());

        Optional<ReportsRepository.ReportRecord> hit = reportsRepository.findByCacheKey(
                schema, definition.type(), format.key(), paramsHash, dataVersion);
        if (hit.isPresent()) {
            log.info("Staff report cache hit: tenantCode={} format={} reportId={} dataVersion={}",
                    tenantCode, format.key(), hit.get().id(), dataVersion);
            return toResponse(tenantCode, format, hit.get(), true);
        }

        ReportWriter writer = writers.get(format);
        if (writer == null) {
            throw new BadRequestException("No writer registered for format: " + format.key());
        }

        Long generatedBy = resolveCallerStaffId(schema, caller);

        List<TenantStaffResponseDTO> rows = definition.fetch(schema, normalized);

        FileAttribute<Set<PosixFilePermission>> ownerOnly =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        Path tmp = null;
        try {
            tmp = Files.createTempFile("report-" + definition.type().toLowerCase(Locale.ROOT) + "-",
                    "." + format.extension(), ownerOnly);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.write(definition.schema(), rows, out);
            }
            long size = Files.size(tmp);

            UUID reportId = UUID.randomUUID();
            String objectKey = buildObjectKey(tenantCode, format, reportId);
            String bucket = storageProperties.getReportsBucket();

            try (var in = Files.newInputStream(tmp)) {
                objectStorageService.upload(bucket, objectKey, in, size, format.contentType());
            }

            ReportsRepository.ReportRecord candidate = new ReportsRepository.ReportRecord(
                    reportId,
                    definition.type(),
                    format.key(),
                    paramsHash,
                    dataVersion,
                    bucket,
                    objectKey,
                    rows.size(),
                    size,
                    generatedBy,
                    null
            );
            boolean inserted;
            ReportsRepository.ReportRecord winning;
            try {
                inserted = reportsRepository.insertIfAbsent(schema, candidate, paramsJson);
                winning = inserted
                        ? reportsRepository.findByCacheKey(schema, definition.type(), format.key(), paramsHash, dataVersion)
                                .orElse(candidate)
                        : reportsRepository.findByCacheKey(schema, definition.type(), format.key(), paramsHash, dataVersion)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Cache row vanished after ON CONFLICT DO NOTHING for hash=" + paramsHash));
            } catch (Exception dbEx) {
                try {
                    objectStorageService.delete(bucket, objectKey);
                } catch (Exception delEx) {
                    log.warn("Failed to delete orphaned report object after DB failure: bucket={} key={}", bucket, objectKey, delEx);
                }
                throw dbEx;
            }

            log.info("Staff report generated: tenantCode={} format={} reportId={} rows={} bytes={} cached={}",
                    tenantCode, format.key(), winning.id(), rows.size(), size, !inserted);
            return toResponse(tenantCode, format, winning, !inserted);
        } catch (IOException e) {
            throw new StorageException("Failed to generate report for tenant " + tenantCode, e);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new StorageException("Failed to generate report for tenant " + tenantCode, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignore) {
                    log.warn("Failed to delete temp report file: {}", tmp);
                }
            }
        }
    }

    private ReportResponseDTO toResponse(String tenantCode, ReportFormat format,
                                         ReportsRepository.ReportRecord rec, boolean cached) {
        Duration ttl = Duration.ofSeconds(storageProperties.getPresignedTtlSeconds());
        String filename = buildDownloadFilename(tenantCode, format, rec.generatedAt());
        String url = objectStorageService
                .presignedGetUrl(rec.bucket(), rec.objectKey(), ttl, filename)
                .toString();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(ttl);
        return ReportResponseDTO.builder()
                .reportId(rec.id())
                .format(rec.format())
                .generatedAt(rec.generatedAt())
                .dataVersion(rec.dataVersion())
                .downloadUrl(url)
                .urlExpiresAt(expiresAt)
                .cached(cached)
                .build();
    }

    private String buildObjectKey(String tenantCode, ReportFormat format, UUID reportId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return String.format("%s/reports/%s/%04d/%02d/%s.%s",
                tenantCode.toLowerCase(Locale.ROOT),
                definition.resourceType().key().toLowerCase(Locale.ROOT),
                now.getYear(),
                now.getMonthValue(),
                reportId,
                format.extension());
    }

    /**
     * User-facing filename, e.g. {@code staff_report_MP_20260519_1422.csv}.
     * Uses the report's persisted {@code generatedAt} so re-downloads of a
     * cached row keep a stable filename.
     */
    private String buildDownloadFilename(String tenantCode, ReportFormat format, OffsetDateTime generatedAt) {
        OffsetDateTime stamp = generatedAt != null ? generatedAt : OffsetDateTime.now(ZoneOffset.UTC);
        return String.format("%s_%s_%s.%s",
                definition.downloadFilenamePrefix(),
                tenantCode.toUpperCase(Locale.ROOT),
                FILENAME_TIMESTAMP.format(stamp.atZoneSameInstant(ZoneOffset.UTC)),
                format.extension());
    }

    private Long resolveCallerStaffId(String schema, Authentication caller) {
        String keycloakUuid = SecurityUtils.getKeycloakId(caller);
        TenantUserRecord user = userTenantRepository.findUserByKeycloakUuid(schema, keycloakUuid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Calling user not found in tenant: " + keycloakUuid));
        return user.id();
    }

    /** Stable JSON for hashing: sorted keys, nulls preserved, no whitespace. */
    private String toCanonicalJson(StaffReportRequestDTO dto) {
        Map<String, Object> tree = new TreeMap<>();
        tree.put("roles", dto.roles());
        tree.put("status", dto.status());
        tree.put("name", dto.name());
        try {
            return canonicalMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize report params", e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Map<ReportFormat, ReportWriter> indexByFormat(List<ReportWriter> writers) {
        Map<ReportFormat, ReportWriter> map = new EnumMap<>(ReportFormat.class);
        for (ReportWriter w : writers) {
            ReportWriter prev = map.putIfAbsent(w.format(), w);
            if (prev != null) {
                throw new IllegalStateException(
                        "Multiple report writers registered for format: " + w.format());
            }
        }
        return map;
    }
}
