package org.arghyam.jalsoochak.scheme.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.dto.CodeCountDTO;
import org.arghyam.jalsoochak.scheme.dto.ReportLinkResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadErrorDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.exception.FileValidationException;
import org.arghyam.jalsoochak.scheme.exception.UnsupportedFileTypeException;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.repository.SchemeLgdMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeSubdivisionMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeUpdateRecord;
import org.arghyam.jalsoochak.scheme.util.TenantSchemaResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SchemeServiceImpl implements SchemeService {

    private static final int MAX_VALIDATION_ERRORS = 1000;
    private static final int CHUNK_SIZE = 1000;
    private static final String SCHEME_TOPIC = "scheme-service-topic";

    // New upload contract:
    // - `center_scheme_id` (CSV) maps to DB `centre_scheme_id`
    // - `achieved_fhtc` maps to DB `fhtc_count`
    // Optional: planned_fhtc, achieved_fhtc, latitude, longitude, operating_status
    // Mandatory: state_scheme_id, center/centre_scheme_id, scheme_name, house_hold_count, work_status
    private static final List<String> SCHEME_HEADERS_V3 = List.of(
            "state_scheme_id",
            "center_scheme_id",
            "scheme_name",
            "planned_fhtc",
            "achieved_fhtc",
            "house_hold_count",
            "longitude",
            "latitude",
            "work_status",
            "operating_status"
    );
    private static final List<String> SCHEME_HEADERS_V3_LEGACY_CENTRE = List.of(
            "state_scheme_id",
            "centre_scheme_id",
            "scheme_name",
            "planned_fhtc",
            "achieved_fhtc",
            "house_hold_count",
            "longitude",
            "latitude",
            "work_status",
            "operating_status"
    );

    private static final List<String> MAPPING_HEADERS_V2 = List.of(
            "scheme_id",
            "parent_lgd_id",
            "parent_lgd_level"
    );
    private static final List<String> MAPPING_HEADERS_V3 = List.of(
            "scheme_id",
            "parent_lgd_id",
            "parent_lgd_level",
            "parent_department_id",
            "parent_department_level"
    );
    private static final List<String> MAPPING_HEADERS_V4 = List.of(
            "state_scheme_id",
            "village_lgd_code",
            "sub_division_name"
    );

    private static final Map<String, Integer> WORK_STATUS_MAP = Map.of(
            "1", 1,
            "ongoing", 1,
            "2", 2,
            "completed", 2,
            "3", 3,
            "not started", 3,
            "4", 4,
            "handed over", 4
    );

    private static final Map<String, Integer> OPERATING_STATUS_MAP = Map.of(
            "1", 1,
            "operative", 1,
            "0", 0,
            "non-operative", 0,
            "2", 2,
            "partially operative", 2
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("csv", "xlsx");
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();
    private static final String REPORT_FORMAT_CSV = "csv";
    private final ConcurrentHashMap<String, Object> reportGenerationLocks = new ConcurrentHashMap<>();

    private final SchemeDbRepository schemeDbRepository;
    private final SchemeUploadChunkProcessor chunkProcessor;
    private final KafkaProducer kafkaProducer;
    private final MinioService minioService;

    @Override
    public PageResponseDTO<SchemeDTO> listSchemes(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            String stateSchemeId,
            String schemeName,
            String name,
            String workStatus,
            String operatingStatus,
            String status
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);

        Integer workStatusCode = parseWorkStatus(workStatus);
        Integer operatingStatusCode = parseOperatingStatus(operatingStatus);

        int size = clampLimit(limit);
        int p = Math.max(0, page);
        int offset = p * size;

        List<SchemeDTO> rows = schemeDbRepository.listSchemes(
                schemaName,
                stateSchemeId,
                schemeName,
                name,
                workStatusCode,
                operatingStatusCode,
                status,
                sortBy,
                sortDir,
                offset,
                size
        );
        long total = schemeDbRepository.countSchemes(schemaName, stateSchemeId, schemeName, name, workStatusCode, operatingStatusCode, status);
        return PageResponseDTO.of(rows, total, p, size);
    }

    @Override
    public PageResponseDTO<SchemeMappingDTO> listSchemeMappings(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            String name,
            String workStatus,
            String operatingStatus,
            String status,
            String villageLgdCode,
            String subDivisionName
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);

        Integer workStatusCode = parseWorkStatus(workStatus);
        Integer operatingStatusCode = parseOperatingStatus(operatingStatus);

        int size = clampLimit(limit);
        int p = Math.max(0, page);
        int offset = p * size;

        List<SchemeMappingDTO> rows = schemeDbRepository.listSchemeMappings(
                schemaName,
                name,
                workStatusCode,
                operatingStatusCode,
                status,
                villageLgdCode,
                subDivisionName,
                sortBy,
                sortDir,
                offset,
                size
        );
        long total = schemeDbRepository.countSchemeMappings(schemaName, name, workStatusCode, operatingStatusCode, status, villageLgdCode, subDivisionName);
        return PageResponseDTO.of(rows, total, p, size);
    }

    @Override
    public SchemeCountsDTO getSchemeCounts(String tenantCode) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);

        SchemeDbRepository.SchemeCounts counts = schemeDbRepository.countActiveInactiveSchemes(schemaName);
        return SchemeCountsDTO.builder()
                .activeSchemes(counts.activeSchemes())
                .inactiveSchemes(counts.inactiveSchemes())
                .build();
    }

    @Override
    public SchemeStatusCountsDTO getSchemeStatusCounts(String tenantCode) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);

        long total = schemeDbRepository.countSchemesTotal(schemaName);
        SchemeDbRepository.SchemeCounts activeInactive = schemeDbRepository.countActiveInactiveSchemes(schemaName);

        return SchemeStatusCountsDTO.builder()
                .totalSchemes(total)
                .activeSchemes(activeInactive.activeSchemes())
                .inactiveSchemes(activeInactive.inactiveSchemes())
                .statusCounts(List.of(
                        CodeCountDTO.builder().status("ACTIVE").count(activeInactive.activeSchemes()).build(),
                        CodeCountDTO.builder().status("INACTIVE").count(activeInactive.inactiveSchemes()).build()
                ))
                .workStatusCounts(schemeDbRepository.countSchemesByWorkStatus(schemaName))
                .operatingStatusCounts(schemeDbRepository.countSchemesByOperatingStatus(schemaName))
                .build();
    }

    @Override
    public SchemeStatusesResponseDTO getSchemeStatuses(Integer tenantId, int schemeId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        if (schemeId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schemeId must be a positive integer");
        }
        String schemaName = schemeDbRepository.findSchemaNameByTenantId(tenantId);
        if (schemaName == null || schemaName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        SchemeStatusesResponseDTO statuses = schemeDbRepository.findSchemeStatusesById(schemaName, schemeId);
        if (statuses == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found");
        }
        return statuses;
    }

    @Override
    public void updateSchemeStatuses(String tenantCode, int schemeId, SchemeStatusUpdateRequestDTO request) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        if (schemeId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schemeId must be a positive integer");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        Integer workStatusCode = parseWorkStatus(request.getWorkStatus());
        Integer operatingStatusCode = parseOperatingStatus(request.getOperatingStatus());
        if (workStatusCode == null && operatingStatusCode == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "At least one of workStatus or operatingStatus must be provided"
            );
        }

        int updatedBy = resolveCurrentUserId(schemaName);
        Integer tenantId = schemeDbRepository.findTenantIdByUserId(schemaName, updatedBy);
        boolean updated = schemeDbRepository.updateSchemeStatusesById(
                schemaName,
                schemeId,
                workStatusCode,
                operatingStatusCode,
                updatedBy
        );
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found");
        }
        List<SchemeDbRepository.SchemeAnalyticsRow> rows =
                schemeDbRepository.findSchemeAnalyticsRowsBySchemeIds(schemaName, List.of(schemeId));
        publishSchemeDimensionEventsFromRows(tenantId, rows);
    }

    @Override
    public SchemeUploadResponseDTO uploadSchemes(MultipartFile file) {
        String schemaName = requireTenantSchema();
        int actorUserId = resolveCurrentUserId(schemaName);
        Integer tenantId = schemeDbRepository.findTenantIdByUserId(schemaName, actorUserId);
        validateFile(file);

        String extension = extractExtension(file.getOriginalFilename());
        List<String> activeHeaders = resolveHeaders(file, extension, List.of(SCHEME_HEADERS_V3, SCHEME_HEADERS_V3_LEGACY_CENTRE));

        int totalRows = validateSchemes(schemaName, file, extension, activeHeaders);
        ProcessResult processed = processSchemes(schemaName, file, extension, activeHeaders, actorUserId, tenantId);
        if (processed.uploadedRows() == 0 && processed.unchangedRows() == totalRows) {
            throw new FileValidationException("Duplicate upload", List.of(
                    error(0, "file", "No changes detected; uploaded file matches existing records")
            ));
        }

        return SchemeUploadResponseDTO.builder()
                .message("Schemes uploaded successfully")
                .totalRows(totalRows)
                .uploadedRows(processed.uploadedRows())
                .build();
    }

    @Override
    public SchemeUploadResponseDTO uploadSchemeMappings(MultipartFile file) {
        String schemaName = requireTenantSchema();
        int actorUserId = resolveCurrentUserId(schemaName);
        Integer tenantId = schemeDbRepository.findTenantIdByUserId(schemaName, actorUserId);
        validateFile(file);

        String extension = extractExtension(file.getOriginalFilename());
        List<String> activeHeaders = resolveHeaders(file, extension, List.of(MAPPING_HEADERS_V4));

        int totalRows = validateMappings(schemaName, file, extension, activeHeaders);
        MappingProcessResult processed = processMappings(schemaName, file, extension, activeHeaders, actorUserId, tenantId);
        if (processed.uploadedRows() == 0 && processed.unchangedRows() == totalRows) {
            throw new FileValidationException("Duplicate upload", List.of(
                    error(0, "file", "No changes detected; uploaded file matches existing records")
            ));
        }

        return SchemeUploadResponseDTO.builder()
                .message("Scheme mappings uploaded successfully")
                .totalRows(totalRows)
                .uploadedRows(processed.uploadedRows())
                .build();
    }

    @Override
    public ReportLinkResponseDTO downloadSchemesReport() {
        String schemaName = requireTenantSchema();
        int actorUserId = resolveCurrentUserId(schemaName);
        String reportType = "SCHEME";
        String paramsJson = "{}";
        String paramsHash = sha256Hex(paramsJson);
        long dataVersion = schemeDbRepository.currentDataVersion(schemaName, reportType);
        String lockKey = schemaName + "|" + reportType + "|" + dataVersion + "|" + paramsHash;

        String cached = findCachedReportLink(schemaName, reportType, paramsHash, dataVersion);
        if (cached != null) {
            return ReportLinkResponseDTO.builder().link(cached).build();
        }

        Object lock = reportGenerationLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            String secondCheck = findCachedReportLink(schemaName, reportType, paramsHash, dataVersion);
            if (secondCheck != null) {
                return ReportLinkResponseDTO.builder().link(secondCheck).build();
            }
            try {
                return generateAndUploadSchemesReport(schemaName, actorUserId, reportType, dataVersion, paramsJson, paramsHash);
            } finally {
                reportGenerationLocks.remove(lockKey, lock);
            }
        }
    }

    @Override
    public ReportLinkResponseDTO downloadSchemeMappingsReport() {
        String schemaName = requireTenantSchema();
        int actorUserId = resolveCurrentUserId(schemaName);
        String reportType = "SCHEME_MAPPING";
        String paramsJson = "{}";
        String paramsHash = sha256Hex(paramsJson);
        long dataVersion = schemeDbRepository.currentDataVersion(schemaName, reportType);
        String lockKey = schemaName + "|" + reportType + "|" + dataVersion + "|" + paramsHash;

        String cached = findCachedReportLink(schemaName, reportType, paramsHash, dataVersion);
        if (cached != null) {
            return ReportLinkResponseDTO.builder().link(cached).build();
        }

        Object lock = reportGenerationLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            String secondCheck = findCachedReportLink(schemaName, reportType, paramsHash, dataVersion);
            if (secondCheck != null) {
                return ReportLinkResponseDTO.builder().link(secondCheck).build();
            }
            try {
                return generateAndUploadSchemeMappingsReport(schemaName, actorUserId, reportType, dataVersion, paramsJson, paramsHash);
            } finally {
                reportGenerationLocks.remove(lockKey, lock);
            }
        }
    }

    private void saveReportRecord(
            String schemaName,
            int actorUserId,
            String reportType,
            String format,
            String paramsJson,
            String paramsHash,
            long dataVersion,
            String objectKey,
            long fileSizeBytes,
            int rowCount
    ) {
        schemeDbRepository.insertReportRecord(
                schemaName,
                UUID.randomUUID().toString(),
                reportType,
                format,
                paramsHash,
                paramsJson,
                dataVersion,
                minioService.getBucket(),
                objectKey,
                rowCount,
                fileSizeBytes,
                actorUserId
        );
    }

    private String findCachedReportLink(String schemaName, String reportType, String paramsHash, long dataVersion) {
        return schemeDbRepository.findReportObjectKey(schemaName, reportType, REPORT_FORMAT_CSV, paramsHash, dataVersion)
                .map(minioService::getObjectUrl)
                .orElse(null);
    }

    private ReportLinkResponseDTO generateAndUploadSchemesReport(
            String schemaName,
            int actorUserId,
            String reportType,
            long dataVersion,
            String paramsJson,
            String paramsHash
    ) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("scheme-report-", ".csv");
            AtomicInteger rowCount = new AtomicInteger(0);

            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8);
                 var csv = CSVFormat.DEFAULT.builder()
                         .setHeader(
                                 "id", "uuid", "state_scheme_id", "centre_scheme_id", "scheme_name",
                                 "fhtc_count", "planned_fhtc", "house_hold_count", "latitude",
                                 "longitude", "channel", "work_status", "operating_status")
                         .build().print(writer)) {
                schemeDbRepository.streamAllSchemes(schemaName, row -> {
                    try {
                        csv.printRecord(
                                row.getId(),
                                row.getUuid(),
                                row.getStateSchemeId(),
                                row.getCentreSchemeId(),
                                row.getSchemeName(),
                                row.getFhtcCount(),
                                row.getPlannedFhtc(),
                                row.getHouseHoldCount(),
                                row.getLatitude(),
                                row.getLongitude(),
                                row.getChannel(),
                                row.getWorkStatus(),
                                row.getOperatingStatus()
                        );
                        rowCount.incrementAndGet();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                csv.flush();
            }

            String objectKey = buildObjectKey(schemaName, "scheme", "csv");
            long fileSize = Files.size(tempFile);
            String link;
            try (InputStream input = Files.newInputStream(tempFile)) {
                link = minioService.upload(input, fileSize, objectKey, "text/csv");
            }
            saveReportRecord(schemaName, actorUserId, reportType, REPORT_FORMAT_CSV, paramsJson, paramsHash, dataVersion, objectKey, fileSize, rowCount.get());
            return ReportLinkResponseDTO.builder().link(link).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate scheme report", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private ReportLinkResponseDTO generateAndUploadSchemeMappingsReport(
            String schemaName,
            int actorUserId,
            String reportType,
            long dataVersion,
            String paramsJson,
            String paramsHash
    ) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("scheme-mapping-report-", ".csv");
            AtomicInteger rowCount = new AtomicInteger(0);

            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8);
                 var csv = CSVFormat.DEFAULT.builder()
                         .setHeader("id", "scheme_id", "state_scheme_id", "scheme_name", "village_lgd_code", "village_name", "sub_division_name")
                         .build().print(writer)) {
                schemeDbRepository.streamAllSchemeMappings(schemaName, row -> {
                    try {
                        csv.printRecord(
                                row.id(),
                                row.schemeId(),
                                row.stateSchemeId(),
                                row.schemeName(),
                                row.villageLgdCode(),
                                row.villageName(),
                                row.subDivisionName()
                        );
                        rowCount.incrementAndGet();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                csv.flush();
            }

            String objectKey = buildObjectKey(schemaName, "scheme_mapping", "csv");
            long fileSize = Files.size(tempFile);
            String link;
            try (InputStream input = Files.newInputStream(tempFile)) {
                link = minioService.upload(input, fileSize, objectKey, "text/csv");
            }
            saveReportRecord(schemaName, actorUserId, reportType, REPORT_FORMAT_CSV, paramsJson, paramsHash, dataVersion, objectKey, fileSize, rowCount.get());
            return ReportLinkResponseDTO.builder().link(link).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate scheme-mapping report", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private String buildObjectKey(String schemaName, String reportType, String format) {
        return "reports/" + schemaName + "/" + reportType + "/"
                + OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond() + "-" + UUID.randomUUID() + "." + format;
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException(
                    "Uploaded file is empty",
                    List.of(error(0, "file", "Please upload a non-empty CSV or XLSX file"))
            );
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new UnsupportedFileTypeException("Only .csv and .xlsx files are supported");
        }
    }

    private List<String> resolveHeaders(MultipartFile file, String extension, List<List<String>> allowedHeaderVariants) {
        try {
            if ("csv".equals(extension)) {
                return resolveCsvHeaders(file, allowedHeaderVariants);
            }
            return resolveXlsxHeaders(file, allowedHeaderVariants);
        } catch (IOException ex) {
            throw new FileValidationException(
                    "Failed to read uploaded file",
                    List.of(error(0, "file", "Unable to read file content"))
            );
        }
    }

    private List<String> resolveCsvHeaders(MultipartFile file, List<List<String>> allowedHeaderVariants) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreSurroundingSpaces(true).build().parse(reader)) {

            var it = parser.iterator();
            if (!it.hasNext()) {
                throw new FileValidationException(
                        "Uploaded file is empty",
                        List.of(error(0, "file", "Header row is missing"))
                );
            }

            List<String> headers = new ArrayList<>();
            CSVRecord header = it.next();
            header.forEach(headers::add);
            return resolveHeaderVariant(headers, allowedHeaderVariants);
        }
    }

    private List<String> resolveXlsxHeaders(MultipartFile file, List<List<String>> allowedHeaderVariants) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new FileValidationException(
                        "Uploaded file is empty",
                        List.of(error(0, "file", "Worksheet is missing"))
                );
            }

            int firstRowIndex = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRowIndex);
            if (headerRow == null) {
                throw new FileValidationException(
                        "Invalid headers",
                        List.of(error(1, "header", "Header row is missing"))
                );
            }

            return resolveHeaderVariant(readExcelHeaders(headerRow), allowedHeaderVariants);
        }
    }

    private List<String> readExcelHeaders(Row headerRow) {
        int size = Math.max(headerRow.getLastCellNum(), (short) 0);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            headers.add(getExcelValue(headerRow, i));
        }
        return headers;
    }

    private List<String> indexedValues(CSVRecord record, List<String> headers) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            values.add(i < record.size() ? normalize(record.get(i)) : "");
        }
        return values;
    }

    private List<String> indexedValues(Row row, List<String> headers) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            values.add(getExcelValue(row, i));
        }
        return values;
    }

    private Map<String, String> rowAsMap(List<String> values, List<String> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.put(headers.get(i), normalize(values.get(i)));
        }
        return map;
    }

    private String getExcelValue(Row row, int index) {
        if (row == null || row.getCell(index) == null) {
            return "";
        }
        return normalize(DATA_FORMATTER.formatCellValue(row.getCell(index)));
    }

    private List<String> resolveHeaderVariant(List<String> rawHeaders, List<List<String>> allowedHeaderVariants) {
        List<String> normalized = rawHeaders.stream().map(this::normalize).toList();
        for (List<String> variant : allowedHeaderVariants) {
            if (normalized.equals(variant)) {
                return variant;
            }
        }

        String expected = allowedHeaderVariants.stream()
                .map(v -> String.join(",", v))
                .reduce((a, b) -> a + " OR " + b)
                .orElse("");

        throw new FileValidationException(
                "Invalid headers",
                List.of(error(1, "header", "Header row must be exactly: " + expected))
        );
    }

    private int validateSchemes(String schemaName, MultipartFile file, String extension, List<String> activeHeaders) {
        List<SchemeUploadErrorDTO> errors = new ArrayList<>();
        final int[] total = {0};
        Set<String> seenStateSchemeIds = new HashSet<>();
        List<SchemeRow> chunk = new ArrayList<>(CHUNK_SIZE);

        try {
            streamRows(file, extension, activeHeaders, (rowNumber, values) -> {
                if (isAllBlank(values)) {
                    return;
                }
                total[0]++;

                int before = errors.size();

                requireField(values, rowNumber, "state_scheme_id", errors);
                if (values.containsKey("center_scheme_id")) {
                    requireField(values, rowNumber, "center_scheme_id", errors);
                } else {
                    requireField(values, rowNumber, "centre_scheme_id", errors);
                }
                requireField(values, rowNumber, "scheme_name", errors);
                requireField(values, rowNumber, "house_hold_count", errors);
                requireField(values, rowNumber, "work_status", errors);

                String stateSchemeId = normalize(values.get("state_scheme_id"));
                if (!stateSchemeId.isBlank()) {
                    String key = stateSchemeId.toLowerCase(Locale.ROOT);
                    if (!seenStateSchemeIds.add(key)) {
                        errors.add(error(rowNumber, "state_scheme_id", "Duplicate state_scheme_id in uploaded file"));
                    }
                }

                // Optional: planned_fhtc, achieved_fhtc, latitude, longitude, operating_status
                parseInteger(values.get("planned_fhtc"), rowNumber, "planned_fhtc", errors);
                parseInteger(values.get("achieved_fhtc"), rowNumber, "achieved_fhtc", errors);
                parseInteger(values.get("house_hold_count"), rowNumber, "house_hold_count", errors);
                parseDouble(values.get("latitude"), rowNumber, "latitude", errors);
                parseDouble(values.get("longitude"), rowNumber, "longitude", errors);
                parseEnum(values.get("work_status"), rowNumber, "work_status", WORK_STATUS_MAP, "Ongoing, Completed, Not Started, Handed Over or 1/2/3/4", errors);
                if (!normalize(values.get("operating_status")).isBlank()) {
                    parseEnum(values.get("operating_status"), rowNumber, "operating_status", OPERATING_STATUS_MAP, "Operative, Non-Operative, Partially Operative or 0/1/2", errors);
                }

                if (errors.size() == before && !stateSchemeId.isBlank()) {
                    chunk.add(new SchemeRow(rowNumber, stateSchemeId));
                }

                if (chunk.size() >= CHUNK_SIZE) {
                    validateSchemeChunk(schemaName, chunk, errors);
                    chunk.clear();
                }

                if (errors.size() > before && errors.size() >= MAX_VALIDATION_ERRORS) {
                    errors.add(error(rowNumber, "file", "Too many validation errors; showing first " + MAX_VALIDATION_ERRORS));
                    throw new TooManyErrorsException();
                }
            });
        } catch (TooManyErrorsException ignored) {
            // stop early (errors already captured)
        } catch (IOException ex) {
            throw new FileValidationException(
                    "Failed to read uploaded file",
                    List.of(error(0, "file", "Unable to read file content"))
            );
        }

        if (!chunk.isEmpty()) {
            validateSchemeChunk(schemaName, chunk, errors);
        }

        if (!errors.isEmpty()) {
            throw new FileValidationException("Validation failed for uploaded file", errors);
        }
        if (total[0] == 0) {
            throw new FileValidationException(
                    "No data rows found in uploaded file",
                    List.of(error(0, "file", "At least one data row is required"))
            );
        }
        return total[0];
    }

    private ProcessResult processSchemes(
            String schemaName,
            MultipartFile file,
            String extension,
            List<String> activeHeaders,
            int actorUserId,
            Integer tenantId
    ) {
        List<SchemeCreateRecord> chunk = new ArrayList<>(CHUNK_SIZE);
        final int[] uploaded = {0};
        final int[] unchanged = {0};

        try {
            streamRows(file, extension, activeHeaders, (rowNumber, values) -> {
                if (isAllBlank(values)) {
                    return;
                }

                // Validation already ran. Keep processing tight and avoid allocating error objects.
                String centreField = values.containsKey("center_scheme_id") ? "center_scheme_id" : "centre_scheme_id";

                String plannedRaw = normalize(values.get("planned_fhtc"));
                String achievedRaw = normalize(values.get("achieved_fhtc"));
                Integer plannedFhtc = plannedRaw.isBlank() ? 0 : Integer.parseInt(plannedRaw);
                Integer fhtcCount = achievedRaw.isBlank() ? 0 : Integer.parseInt(achievedRaw);
                Integer houseHoldCount = Integer.parseInt(normalize(values.get("house_hold_count")));
                String latRaw = normalize(values.get("latitude"));
                String lonRaw = normalize(values.get("longitude"));
                Double latitude = latRaw.isBlank() ? null : Double.parseDouble(latRaw);
                Double longitude = lonRaw.isBlank() ? null : Double.parseDouble(lonRaw);
                Integer channel = null;
                Integer workStatus = WORK_STATUS_MAP.get(normalize(values.get("work_status")).toLowerCase());
                String operatingRaw = normalize(values.get("operating_status")).toLowerCase();
                Integer operatingStatus = operatingRaw.isBlank() ? 1 : OPERATING_STATUS_MAP.get(operatingRaw);

                chunk.add(new SchemeCreateRecord(
                        UUID.randomUUID().toString(),
                        normalize(values.get("state_scheme_id")),
                        normalize(values.get(centreField)),
                        normalize(values.get("scheme_name")),
                        fhtcCount,
                        plannedFhtc,
                        houseHoldCount,
                        latitude,
                        longitude,
                        channel,
                        workStatus,
                        operatingStatus,
                        actorUserId,
                        actorUserId
                ));

                if (chunk.size() >= CHUNK_SIZE) {
                    // Upsert and clear.
                    // Use a copy so the chunk processor can safely retain the list if needed.
                    UpsertResult result = upsertSchemesChunk(schemaName, new ArrayList<>(chunk), actorUserId, tenantId);
                    uploaded[0] += result.uploaded();
                    unchanged[0] += result.unchanged();
                    chunk.clear();
                }
            });
        } catch (IOException ex) {
            throw new FileValidationException(
                    "Failed to read uploaded file",
                    List.of(error(0, "file", "Unable to read file content"))
            );
        }

        if (!chunk.isEmpty()) {
            UpsertResult result = upsertSchemesChunk(schemaName, chunk, actorUserId, tenantId);
            uploaded[0] += result.uploaded();
            unchanged[0] += result.unchanged();
        }
        return new ProcessResult(uploaded[0], unchanged[0]);
    }

    private UpsertResult upsertSchemesChunk(
            String schemaName,
            List<SchemeCreateRecord> rows,
            int actorUserId,
            Integer tenantId
    ) {
        if (rows == null || rows.isEmpty()) {
            return new UpsertResult(0, 0);
        }

        List<String> stateSchemeIds = new ArrayList<>(rows.size());
        for (SchemeCreateRecord row : rows) {
            stateSchemeIds.add(row.stateSchemeId());
        }
        Map<String, Integer> existing = schemeDbRepository.findSchemeIdsByStateSchemeIds(schemaName, stateSchemeIds);
        Map<String, SchemeDbRepository.SchemeSnapshot> existingSnapshots =
                schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(schemaName, stateSchemeIds);

        List<SchemeCreateRecord> inserts = new ArrayList<>(rows.size());
        List<SchemeUpdateRecord> updates = new ArrayList<>();
        int unchanged = 0;
        for (SchemeCreateRecord row : rows) {
            String key = row.stateSchemeId() == null ? "" : row.stateSchemeId().trim().toLowerCase(Locale.ROOT);
            Integer existingId = existing.get(key);
            if (existingId == null) {
                inserts.add(row);
            } else {
                SchemeDbRepository.SchemeSnapshot snapshot = existingSnapshots.get(key);
                if (snapshot != null && isSchemeUnchanged(row, snapshot)) {
                    unchanged++;
                    continue;
                }
                updates.add(new SchemeUpdateRecord(
                        existingId,
                        row.stateSchemeId(),
                        row.centreSchemeId(),
                        row.schemeName(),
                        row.fhtcCount(),
                        row.plannedFhtc(),
                        row.houseHoldCount(),
                        row.latitude(),
                        row.longitude(),
                        row.workStatus(),
                        row.operatingStatus(),
                        actorUserId
                ));
            }
        }

        int uploaded = 0;
        if (!inserts.isEmpty()) {
            uploaded += chunkProcessor.insertSchemesChunk(schemaName, inserts);
        }
        if (!updates.isEmpty()) {
            uploaded += chunkProcessor.updateSchemesChunk(schemaName, updates);
        }
        if (uploaded > 0) {
            List<String> changedStateSchemeIds = new ArrayList<>(inserts.size() + updates.size());
            for (SchemeCreateRecord row : inserts) {
                changedStateSchemeIds.add(row.stateSchemeId());
            }
            for (SchemeUpdateRecord row : updates) {
                changedStateSchemeIds.add(row.stateSchemeId());
            }
            publishSchemeDimensionEvents(schemaName, tenantId, changedStateSchemeIds);
        }
        return new UpsertResult(uploaded, unchanged);
    }

    private int validateMappings(String schemaName, MultipartFile file, String extension, List<String> activeHeaders) {
        List<SchemeUploadErrorDTO> errors = new ArrayList<>();
        List<MappingRow> chunk = new ArrayList<>(CHUNK_SIZE);
        final int[] total = {0};
        Set<String> seenMappingKeys = new HashSet<>();

        try {
            streamRows(file, extension, activeHeaders, (rowNumber, values) -> {
                if (isAllBlank(values)) {
                    return;
                }
                total[0]++;

                int before = errors.size();

                requireField(values, rowNumber, "state_scheme_id", errors);
                requireField(values, rowNumber, "village_lgd_code", errors);
                requireField(values, rowNumber, "sub_division_name", errors);

                boolean rowHasErrors = errors.size() != before;
                if (!rowHasErrors) {
                    String stateSchemeId = normalize(values.get("state_scheme_id")).toLowerCase(Locale.ROOT);
                    String villageCode = normalize(values.get("village_lgd_code")).toLowerCase(Locale.ROOT);
                    String subDivision = normalize(values.get("sub_division_name")).toLowerCase(Locale.ROOT);
                    String key = stateSchemeId + "|" + villageCode + "|" + subDivision;
                    if (!seenMappingKeys.add(key)) {
                        errors.add(error(rowNumber, "state_scheme_id", "Duplicate mapping in uploaded file"));
                        rowHasErrors = true;
                    }
                }
                if (!rowHasErrors) {
                    chunk.add(new MappingRow(
                            rowNumber,
                            normalize(values.get("state_scheme_id")),
                            normalize(values.get("village_lgd_code")),
                            normalize(values.get("sub_division_name"))
                    ));
                }

                if (chunk.size() >= CHUNK_SIZE) {
                    validateMappingChunk(schemaName, chunk, errors);
                    chunk.clear();
                }

                if (errors.size() > before && errors.size() >= MAX_VALIDATION_ERRORS) {
                    errors.add(error(rowNumber, "file", "Too many validation errors; showing first " + MAX_VALIDATION_ERRORS));
                    throw new TooManyErrorsException();
                }
            });
        } catch (TooManyErrorsException ignored) {
            // stop early (errors already captured)
        } catch (IOException ex) {
            throw new FileValidationException(
                    "Failed to read uploaded file",
                    List.of(error(0, "file", "Unable to read file content"))
            );
        }

        if (!chunk.isEmpty()) {
            validateMappingChunk(schemaName, chunk, errors);
        }

        if (!errors.isEmpty()) {
            throw new FileValidationException("Validation failed for uploaded file", errors);
        }
        if (total[0] == 0) {
            throw new FileValidationException(
                    "No data rows found in uploaded file",
                    List.of(error(0, "file", "At least one data row is required"))
            );
        }
        return total[0];
    }

    private void validateMappingChunk(
            String schemaName,
            List<MappingRow> rows,
            List<SchemeUploadErrorDTO> errors
    ) {
        List<String> stateSchemeIds = new ArrayList<>(rows.size());
        List<String> villageCodes = new ArrayList<>(rows.size());
        List<String> subDivisionNames = new ArrayList<>(rows.size());

        for (MappingRow r : rows) {
            stateSchemeIds.add(r.stateSchemeId());
            villageCodes.add(r.villageLgdCode());
            subDivisionNames.add(r.subDivisionName());
        }

        Map<String, Integer> schemeIdsByStateSchemeId = schemeDbRepository.findSchemeIdsByStateSchemeIds(schemaName, stateSchemeIds);
        Map<String, Integer> lgdIdsByCode = schemeDbRepository.findLgdIdsByCodes(schemaName, villageCodes);
        Map<String, Integer> deptIdsByTitle = schemeDbRepository.findDepartmentIdsByTitles(schemaName, subDivisionNames);

        for (MappingRow r : rows) {
            Integer schemeId = schemeIdsByStateSchemeId.get(r.stateSchemeId().toLowerCase(Locale.ROOT));
            if (schemeId == null) {
                errors.add(error(r.rowNumber(), "state_scheme_id", "state_scheme_id does not exist"));
                continue;
            }
            Integer lgdId = lgdIdsByCode.get(r.villageLgdCode().toLowerCase(Locale.ROOT));
            if (lgdId == null) {
                errors.add(error(r.rowNumber(), "village_lgd_code", "village_lgd_code does not exist"));
                continue;
            }
            Integer deptId = deptIdsByTitle.get(r.subDivisionName().toLowerCase(Locale.ROOT));
            if (deptId == null) {
                errors.add(error(r.rowNumber(), "sub_division_name", "sub_division_name does not exist"));
                continue;
            }
        }
    }

    private void validateSchemeChunk(
            String schemaName,
            List<SchemeRow> rows,
            List<SchemeUploadErrorDTO> errors
    ) {
        // Allow overwriting existing schemes on re-upload (no DB-duplicate validation needed).
    }

    private MappingProcessResult processMappings(
            String schemaName,
            MultipartFile file,
            String extension,
            List<String> activeHeaders,
            int actorUserId,
            Integer tenantId
    ) {
        List<MappingRow> rows = new ArrayList<>(CHUNK_SIZE);

        try {
            streamRows(file, extension, activeHeaders, (rowNumber, values) -> {
                if (isAllBlank(values)) {
                    return;
                }
                rows.add(new MappingRow(
                        rowNumber,
                        normalize(values.get("state_scheme_id")),
                        normalize(values.get("village_lgd_code")),
                        normalize(values.get("sub_division_name"))
                ));
            });
        } catch (IOException ex) {
            throw new FileValidationException(
                    "Failed to read uploaded file",
                    List.of(error(0, "file", "Unable to read file content"))
            );
        }

        if (rows.isEmpty()) {
            return new MappingProcessResult(0, 0);
        }

        List<String> stateSchemeIds = new ArrayList<>(rows.size());
        List<String> villageCodes = new ArrayList<>(rows.size());
        List<String> subDivisionNames = new ArrayList<>(rows.size());
        for (MappingRow r : rows) {
            stateSchemeIds.add(r.stateSchemeId());
            villageCodes.add(r.villageLgdCode());
            subDivisionNames.add(r.subDivisionName());
        }

        Map<String, Integer> schemeIdsByStateSchemeId = schemeDbRepository.findSchemeIdsByStateSchemeIds(schemaName, stateSchemeIds);
        Map<String, Integer> lgdIdsByCode = schemeDbRepository.findLgdIdsByCodes(schemaName, villageCodes);
        Map<String, Integer> deptIdsByTitle = schemeDbRepository.findDepartmentIdsByTitles(schemaName, subDivisionNames);

        Map<Integer, List<ResolvedMappingRow>> rowsByScheme = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> desiredLgdByScheme = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> desiredDeptByScheme = new LinkedHashMap<>();

        for (MappingRow r : rows) {
            Integer schemeId = schemeIdsByStateSchemeId.get(r.stateSchemeId().toLowerCase(Locale.ROOT));
            Integer lgdId = lgdIdsByCode.get(r.villageLgdCode().toLowerCase(Locale.ROOT));
            Integer deptId = deptIdsByTitle.get(r.subDivisionName().toLowerCase(Locale.ROOT));
            if (schemeId == null || lgdId == null || deptId == null) {
                continue;
            }
            rowsByScheme.computeIfAbsent(schemeId, k -> new ArrayList<>())
                    .add(new ResolvedMappingRow(schemeId, lgdId, deptId));
            desiredLgdByScheme.computeIfAbsent(schemeId, k -> new HashSet<>()).add(lgdId);
            desiredDeptByScheme.computeIfAbsent(schemeId, k -> new HashSet<>()).add(deptId);
        }

        if (rowsByScheme.isEmpty()) {
            return new MappingProcessResult(0, 0);
        }

        List<Integer> schemeIds = new ArrayList<>(rowsByScheme.keySet());
        Map<Integer, Set<Integer>> existingLgdByScheme = schemeDbRepository.findSchemeLgdMappingsBySchemeIds(schemaName, schemeIds);
        Map<Integer, Set<Integer>> existingDeptByScheme = schemeDbRepository.findSchemeDepartmentMappingsBySchemeIds(schemaName, schemeIds);

        List<Integer> schemesToClear = new ArrayList<>();
        List<SchemeLgdMappingCreateRecord> lgd = new ArrayList<>();
        List<SchemeSubdivisionMappingCreateRecord> dept = new ArrayList<>();
        int uploaded = 0;
        int unchanged = 0;

        for (Map.Entry<Integer, List<ResolvedMappingRow>> entry : rowsByScheme.entrySet()) {
            Integer schemeId = entry.getKey();
            Set<Integer> desiredLgd = desiredLgdByScheme.getOrDefault(schemeId, Set.of());
            Set<Integer> desiredDept = desiredDeptByScheme.getOrDefault(schemeId, Set.of());
            Set<Integer> existingLgd = existingLgdByScheme.getOrDefault(schemeId, Set.of());
            Set<Integer> existingDept = existingDeptByScheme.getOrDefault(schemeId, Set.of());

            if (existingLgd.equals(desiredLgd) && existingDept.equals(desiredDept)) {
                unchanged += entry.getValue().size();
                continue;
            }

            schemesToClear.add(schemeId);
            for (ResolvedMappingRow r : entry.getValue()) {
                lgd.add(new SchemeLgdMappingCreateRecord(
                        r.schemeId(),
                        r.lgdId(),
                        6,
                        actorUserId,
                        actorUserId
                ));
                dept.add(new SchemeSubdivisionMappingCreateRecord(
                        r.schemeId(),
                        r.departmentId(),
                        "sub_division",
                        actorUserId,
                        actorUserId
                ));
            }
            uploaded += entry.getValue().size();
        }

        if (!schemesToClear.isEmpty()) {
            schemeDbRepository.clearSchemeMappingsForSchemes(schemaName, schemesToClear, actorUserId);
        }
        insertMappingsInChunks(schemaName, lgd, dept);
        if (!schemesToClear.isEmpty()) {
            List<SchemeDbRepository.SchemeAnalyticsRow> updatedSchemes =
                    schemeDbRepository.findSchemeAnalyticsRowsBySchemeIds(schemaName, new ArrayList<>(rowsByScheme.keySet()));
            if (!updatedSchemes.isEmpty()) {
                publishSchemeDimensionEventsFromRows(tenantId, updatedSchemes);
            }
        }

        return new MappingProcessResult(uploaded, unchanged);
    }

    private void insertMappingsInChunks(
            String schemaName,
            List<SchemeLgdMappingCreateRecord> lgdRows,
            List<SchemeSubdivisionMappingCreateRecord> deptRows
    ) {
        if ((lgdRows == null || lgdRows.isEmpty()) && (deptRows == null || deptRows.isEmpty())) {
            return;
        }

        int max = Math.max(lgdRows == null ? 0 : lgdRows.size(), deptRows == null ? 0 : deptRows.size());
        for (int i = 0; i < max; i += CHUNK_SIZE) {
            int lgdEnd = lgdRows == null ? 0 : Math.min(i + CHUNK_SIZE, lgdRows.size());
            int deptEnd = deptRows == null ? 0 : Math.min(i + CHUNK_SIZE, deptRows.size());
            List<SchemeLgdMappingCreateRecord> lgdChunk =
                    lgdRows == null ? List.of() : lgdRows.subList(i, lgdEnd);
            List<SchemeSubdivisionMappingCreateRecord> deptChunk =
                    deptRows == null ? List.of() : deptRows.subList(i, deptEnd);
            if (!lgdChunk.isEmpty() || !deptChunk.isEmpty()) {
                chunkProcessor.insertMappingsChunk(schemaName, lgdChunk, deptChunk);
            }
        }
    }

    private void publishSchemeDimensionEvents(String schemaName, Integer tenantId, List<String> stateSchemeIds) {
        if (tenantId == null || stateSchemeIds == null || stateSchemeIds.isEmpty()) {
            return;
        }
        List<SchemeDbRepository.SchemeAnalyticsRow> rows =
                schemeDbRepository.findSchemeAnalyticsRowsByStateSchemeIds(schemaName, stateSchemeIds);
        publishSchemeDimensionEventsFromRows(tenantId, rows);
    }

    private void publishSchemeDimensionEventsFromRows(Integer tenantId, List<SchemeDbRepository.SchemeAnalyticsRow> rows) {
        if (tenantId == null || rows == null || rows.isEmpty()) {
            return;
        }
        for (SchemeDbRepository.SchemeAnalyticsRow row : rows) {
            Integer parentLgd = row.parentLgdId() != null ? row.parentLgdId() : 0;
            Integer parentDept = row.parentDepartmentId();
            int deptLevelFallback = parentDept != null ? parentDept : 0;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "SCHEME_UPDATED");
            payload.put("schemeId", row.schemeId());
            payload.put("tenantId", tenantId);
            payload.put("schemeName", row.schemeName());
            payload.put("stateSchemeId", safeParseInt(row.stateSchemeId()));
            payload.put("centreSchemeId", safeParseInt(row.centreSchemeId()));
            payload.put("longitude", row.longitude());
            payload.put("latitude", row.latitude());
            payload.put("parentLgdLocationId", parentLgd);
            payload.put("level1LgdId", parentLgd);
            payload.put("level2LgdId", parentLgd);
            payload.put("level3LgdId", parentLgd);
            payload.put("level4LgdId", parentLgd);
            payload.put("level5LgdId", parentLgd);
            payload.put("level6LgdId", parentLgd);
            payload.put("parentDepartmentLocationId", parentDept);
            payload.put("level1DeptId", deptLevelFallback);
            payload.put("level2DeptId", deptLevelFallback);
            payload.put("level3DeptId", deptLevelFallback);
            payload.put("level4DeptId", deptLevelFallback);
            payload.put("level5DeptId", deptLevelFallback);
            payload.put("level6DeptId", deptLevelFallback);
            payload.put("status", row.operatingStatus());
            payload.put("operating_status", row.operatingStatus());
            payload.put("work_status", row.workStatus());
            kafkaProducer.publishJson(SCHEME_TOPIC, payload);
        }
    }

    private Integer safeParseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int resolveCurrentUserId(String schemaName) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No valid authentication");
        }
        var jwt = jwtAuth.getToken();

        // Verify the caller's tenant_state_code maps to the requested schema.
        // SUPER_USER tokens carry no tenant_state_code and are allowed through.
        boolean isSuperUser = jwtAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_USER".equals(a.getAuthority()));
        if (!isSuperUser) {
            String tenantStateCode = jwt.getClaimAsString("tenant_state_code");
            if (tenantStateCode == null || tenantStateCode.isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Not authorized to operate on this tenant");
            }
            String expectedSchema = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantStateCode);
            if (!schemaName.equals(expectedSchema)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Not authorized to operate on this tenant");
            }
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            email = jwt.getClaimAsString("preferred_username");
        }
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token missing user identity");
        }
        Integer userId = schemeDbRepository.findUserIdByEmail(schemaName, email);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found for token");
        }
        return userId;
    }

    private String requireTenantSchema() {
        String schemaName = TenantContext.getSchema();
        if (schemaName == null || schemaName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tenant could not be resolved. Ensure X-Tenant-Code header is set."
            );
        }
        return schemaName;
    }

    private int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 100);
    }

    private Integer parseWorkStatus(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank()) {
            return null;
        }
        String k = v.toLowerCase(Locale.ROOT);
        Integer byName = WORK_STATUS_MAP.get(k);
        if (byName != null) {
            return byName;
        }
        try {
            int n = Integer.parseInt(v);
            if (n >= 1 && n <= 4) {
                return n;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid workStatus. Expected one of: Ongoing, Completed, Not Started, Handed Over or 1/2/3/4");
    }

    private Integer parseOperatingStatus(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank()) {
            return null;
        }
        String k = v.toLowerCase(Locale.ROOT);
        Integer byName = OPERATING_STATUS_MAP.get(k);
        if (byName != null) {
            return byName;
        }
        try {
            int n = Integer.parseInt(v);
            if (n >= 0 && n <= 2) {
                return n;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid operatingStatus. Expected one of: Operative, Non-Operative, Partially Operative or 0/1/2");
    }

    private void requireField(Map<String, String> values, int rowNumber, String field, List<SchemeUploadErrorDTO> errors) {
        if (normalize(values.get(field)).isBlank()) {
            errors.add(error(rowNumber, field, field + " is required"));
        }
    }

    private Integer parseInteger(String value, int rowNumber, String field, List<SchemeUploadErrorDTO> errors) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            errors.add(error(rowNumber, field, field + " must be a valid integer"));
            return null;
        }
    }

    private Double parseDouble(String value, int rowNumber, String field, List<SchemeUploadErrorDTO> errors) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            errors.add(error(rowNumber, field, field + " must be a valid decimal number"));
            return null;
        }
    }

    private Integer parseEnum(
            String value,
            int rowNumber,
            String field,
            Map<String, Integer> mapping,
            String expected,
            List<SchemeUploadErrorDTO> errors
    ) {
        String normalized = normalize(value).toLowerCase();
        if (normalized.isBlank()) {
            return null;
        }

        Integer mappedValue = mapping.get(normalized);
        if (mappedValue == null) {
            errors.add(error(rowNumber, field, "Invalid " + field + ". Expected: " + expected));
        }
        return mappedValue;
    }

    private SchemeUploadErrorDTO error(int rowNumber, String field, String message) {
        return SchemeUploadErrorDTO.builder()
                .rowNumber(rowNumber)
                .field(field)
                .message(message)
                .build();
    }

    private String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isSchemeUnchanged(SchemeCreateRecord row, SchemeDbRepository.SchemeSnapshot snapshot) {
        if (row == null || snapshot == null) {
            return false;
        }
        return sameText(row.stateSchemeId(), snapshot.stateSchemeId())
                && sameText(row.centreSchemeId(), snapshot.centreSchemeId())
                && sameText(row.schemeName(), snapshot.schemeName())
                && sameInteger(row.fhtcCount(), snapshot.fhtcCount())
                && sameInteger(row.plannedFhtc(), snapshot.plannedFhtc())
                && sameInteger(row.houseHoldCount(), snapshot.houseHoldCount())
                && sameDouble(row.latitude(), snapshot.latitude())
                && sameDouble(row.longitude(), snapshot.longitude())
                && sameInteger(row.workStatus(), snapshot.workStatus())
                && sameInteger(row.operatingStatus(), snapshot.operatingStatus());
    }

    private boolean sameText(String left, String right) {
        String l = left == null ? "" : left.trim();
        String r = right == null ? "" : right.trim();
        return l.equals(r);
    }

    private boolean sameInteger(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean sameDouble(Double left, Double right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean isAllBlank(Map<String, String> values) {
        for (String v : values.values()) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void streamRows(MultipartFile file, String extension, List<String> activeHeaders, RowConsumer consumer) throws IOException {
        if ("csv".equals(extension)) {
            streamCsv(file, activeHeaders, consumer);
            return;
        }
        streamXlsx(file, activeHeaders, consumer);
    }

    private void streamCsv(MultipartFile file, List<String> activeHeaders, RowConsumer consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreSurroundingSpaces(true).build().parse(reader)) {

            var it = parser.iterator();
            if (!it.hasNext()) {
                return;
            }
            it.next(); // header

            while (it.hasNext()) {
                CSVRecord record = it.next();
                Map<String, String> values = rowAsMap(indexedValues(record, activeHeaders), activeHeaders);
                consumer.accept((int) record.getRecordNumber(), values);
            }
        }
    }

    private void streamXlsx(MultipartFile file, List<String> activeHeaders, RowConsumer consumer) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                return;
            }

            int firstRowIndex = sheet.getFirstRowNum();
            for (int i = firstRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                Map<String, String> values = rowAsMap(indexedValues(row, activeHeaders), activeHeaders);
                consumer.accept(i + 1, values);
            }
        }
    }

    private record MappingRow(
            int rowNumber,
            String stateSchemeId,
            String villageLgdCode,
            String subDivisionName
    ) {
    }

    private record ResolvedMappingRow(
            int schemeId,
            int lgdId,
            int departmentId
    ) {
    }

    private record SchemeRow(
            int rowNumber,
            String stateSchemeId
    ) {
    }

    private record ProcessResult(int uploadedRows, int unchangedRows) {
    }

    private record MappingProcessResult(int uploadedRows, int unchangedRows) {
    }

    private record UpsertResult(int uploaded, int unchanged) {
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(int rowNumber, Map<String, String> values);
    }

    private static final class TooManyErrorsException extends RuntimeException {
    }
}
