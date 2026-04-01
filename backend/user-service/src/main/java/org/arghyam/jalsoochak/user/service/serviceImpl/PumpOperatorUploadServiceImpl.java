package org.arghyam.jalsoochak.user.service.serviceImpl;

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
import org.arghyam.jalsoochak.user.auth.UploadAuthService;
import org.arghyam.jalsoochak.user.config.TenantContext;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorUploadResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.UploadErrorDTO;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.UserUploadRepository;
import org.arghyam.jalsoochak.user.service.GlificPreferredLanguageService;
import org.arghyam.jalsoochak.user.service.PumpOperatorUploadService;
import org.arghyam.jalsoochak.user.service.PumpOperatorUploadChunkProcessor;
import org.arghyam.jalsoochak.user.util.LongHashSet;
import org.arghyam.jalsoochak.user.util.PhoneNumberUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PumpOperatorUploadServiceImpl implements PumpOperatorUploadService {

    private static final List<String> ALLOWED_USER_TYPES = List.of(
            "pump_operator",
            "section_officer",
            "sub_divisional_officer"
    );
    private static final int MAX_VALIDATION_ERRORS = 1000;
    private static final int CHUNK_SIZE = 1000;

    // Strict header contract (order and spelling must match exactly).
    // We accept both legacy `person_type_id` and newer `person_type` for compatibility.
    private static final List<String> EXPECTED_HEADERS_V2 = List.of(
            "first_name",
            "last_name",
            "full_name",
            "phone_number",
            "person_type",
            "state_scheme_id"
    );
    private static final List<String> EXPECTED_HEADERS_V1 = List.of(
            "first_name",
            "last_name",
            "full_name",
            "phone_number",
            "person_type_id",
            "state_scheme_id"
    );
    private static final List<List<String>> ALLOWED_HEADER_VARIANTS = List.of(EXPECTED_HEADERS_V2, EXPECTED_HEADERS_V1);

    private static final DataFormatter DATA_FORMATTER = new DataFormatter(Locale.ROOT);

    private final UploadAuthService uploadAuthService;
    private final UserTenantRepository userTenantRepository;
    private final UserUploadRepository userUploadRepository;
    private final UserCommonRepository userCommonRepository;
    private final GlificPreferredLanguageService preferredLanguageService;
    private final PumpOperatorUploadChunkProcessor chunkProcessor;

    @Override
    public PumpOperatorUploadResponseDTO uploadPumpOperatorMappings(MultipartFile file, String authorizationHeader) {
        String schemaName = requireTenantSchema();
        String tenantCode = toTenantCode(schemaName);
        int actorUserId = uploadAuthService.requireStateAdminUserId(schemaName, authorizationHeader);
        validateFile(file);

        TenantUserRecord actor = userTenantRepository.findUserById(schemaName, (long) actorUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found for token"));
        int preferredLanguageId = preferredLanguageService.resolvePreferredLanguageId(actor.tenantId());

        Map<String, Integer> userTypeIds = resolveUserTypeIds();

        String extension = extractExtension(file.getOriginalFilename());
        Map<String, Integer> schemeIdCache = new HashMap<>();

        int totalRows = validateUpload(schemaName, file, extension, schemeIdCache);
        ProcessResult processed = processUpload(schemaName, tenantCode, actor, actorUserId, userTypeIds, preferredLanguageId, file, extension, schemeIdCache);

        return PumpOperatorUploadResponseDTO.builder()
                .message("Pump operator upload processed successfully")
                .totalRows(totalRows)
                .uploadedRows(processed.uploadedRows())
                .skippedRows(processed.skippedRows())
                .build();
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

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded file is empty", List.of(
                    UploadErrorDTO.builder().row(0).field("file").message("Please upload a non-empty CSV or Excel file").build()
            ));
        }
        String extension = extractExtension(file.getOriginalFilename());
        if (!"csv".equals(extension) && !"xlsx".equals(extension) && !"xls".equals(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only .csv, .xlsx and .xls files are supported");
        }
    }

    private int validateUpload(String schemaName, MultipartFile file, String extension, Map<String, Integer> schemeIdCache) {
        List<UploadErrorDTO> errors = new ArrayList<>();
        LongHashSet seenPhones = new LongHashSet(2048);
        Map<String, Boolean> phoneExistsCache = new HashMap<>();

        int totalRows = "csv".equals(extension)
                ? validateCsv(schemaName, file, schemeIdCache, seenPhones, phoneExistsCache, errors)
                : validateXlsx(schemaName, file, schemeIdCache, seenPhones, phoneExistsCache, errors);

        if (!errors.isEmpty()) {
            throw new BadRequestException("Validation failed for uploaded file", errors);
        }
        if (totalRows == 0) {
            throw new BadRequestException("No data rows found in uploaded file", List.of(
                    UploadErrorDTO.builder().row(0).field("file").message("At least one data row is required").build()
            ));
        }
        return totalRows;
    }

    private ProcessResult processUpload(
            String schemaName,
            String tenantCode,
            TenantUserRecord actor,
            int actorUserId,
            Map<String, Integer> userTypeIds,
            int preferredLanguageId,
            MultipartFile file,
            String extension,
            Map<String, Integer> schemeIdCache
    ) {
        return "csv".equals(extension)
                ? processCsv(schemaName, tenantCode, actor, actorUserId, userTypeIds, preferredLanguageId, file, schemeIdCache)
                : processXlsx(schemaName, tenantCode, actor, actorUserId, userTypeIds, preferredLanguageId, file, schemeIdCache);
    }

    private int validateCsv(
            String schemaName,
            MultipartFile file,
            Map<String, Integer> schemeIdCache,
            LongHashSet seenPhones,
            Map<String, Boolean> phoneExistsCache,
            List<UploadErrorDTO> errors
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreSurroundingSpaces(true).build().parse(reader)) {

            var it = parser.iterator();
            if (!it.hasNext()) {
                errors.add(err(0, "file", "Header row is missing"));
                return 0;
            }

            List<String> rawHeaders = new ArrayList<>();
            CSVRecord header = it.next();
            header.forEach(rawHeaders::add);
            List<String> activeHeaders = resolveHeaderVariant(1, rawHeaders);

            int total = 0;
            while (it.hasNext()) {
                CSVRecord record = it.next();
                Map<String, String> map = toRowMap(activeHeaders, record);
                if (isAllBlank(map)) {
                    continue;
                }
                PumpOperatorUploadChunkProcessor.UploadRow row = toUploadRow((int) record.getRecordNumber(), map);
                total++;
                validateRow(schemaName, row, schemeIdCache, seenPhones, phoneExistsCache, errors);
                if (errors.size() >= MAX_VALIDATION_ERRORS) {
                    errors.add(err(row.rowNumber(), "file", "Too many validation errors; showing first " + MAX_VALIDATION_ERRORS));
                    break;
                }
            }
            return total;
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BadRequestException("Failed to read uploaded file", List.of(
                    UploadErrorDTO.builder().row(0).field("file").message("Unable to read file content").build()
            ));
        }
    }

    private int validateXlsx(
            String schemaName,
            MultipartFile file,
            Map<String, Integer> schemeIdCache,
            LongHashSet seenPhones,
            Map<String, Boolean> phoneExistsCache,
            List<UploadErrorDTO> errors
    ) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                errors.add(err(0, "file", "Worksheet is missing"));
                return 0;
            }

            int firstRowIndex = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRowIndex);
            if (headerRow == null) {
                errors.add(err(1, "header", "Header row is missing"));
                return 0;
            }

            List<String> rawHeaders = new ArrayList<>();
            int headerSize = Math.max(headerRow.getLastCellNum(), (short) 0);
            for (int i = 0; i < headerSize; i++) {
                rawHeaders.add(DATA_FORMATTER.formatCellValue(headerRow.getCell(i)));
            }
            List<String> activeHeaders = resolveHeaderVariant(firstRowIndex + 1, rawHeaders);

            int total = 0;
            for (int i = firstRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                Map<String, String> map = toRowMap(activeHeaders, row);
                if (isAllBlank(map)) {
                    continue;
                }
                PumpOperatorUploadChunkProcessor.UploadRow uploadRow = toUploadRow(i + 1, map);
                total++;
                validateRow(schemaName, uploadRow, schemeIdCache, seenPhones, phoneExistsCache, errors);
                if (errors.size() >= MAX_VALIDATION_ERRORS) {
                    errors.add(err(uploadRow.rowNumber(), "file", "Too many validation errors; showing first " + MAX_VALIDATION_ERRORS));
                    break;
                }
            }
            return total;
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Failed to read uploaded file", List.of(
                    UploadErrorDTO.builder().row(0).field("file").message("Unable to read file content").build()
            ));
        }
    }

    private ProcessResult processCsv(
            String schemaName,
            String tenantCode,
            TenantUserRecord actor,
            int actorUserId,
            Map<String, Integer> userTypeIds,
            int preferredLanguageId,
            MultipartFile file,
            Map<String, Integer> schemeIdCache
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreSurroundingSpaces(true).build().parse(reader)) {

            var it = parser.iterator();
            if (!it.hasNext()) {
                return new ProcessResult(0, 0);
            }

            List<String> rawHeaders = new ArrayList<>();
            CSVRecord header = it.next();
            header.forEach(rawHeaders::add);
            List<String> activeHeaders = resolveHeaderVariant(1, rawHeaders);

            int uploaded = 0;
            int skipped = 0;
            List<PumpOperatorUploadChunkProcessor.UploadRow> chunk = new ArrayList<>(CHUNK_SIZE);
            while (it.hasNext()) {
                CSVRecord record = it.next();
                Map<String, String> map = toRowMap(activeHeaders, record);
                if (isAllBlank(map)) {
                    continue;
                }
                chunk.add(toUploadRow((int) record.getRecordNumber(), map));
                if (chunk.size() >= CHUNK_SIZE) {
                    var res = chunkProcessor.processChunk(schemaName, tenantCode, actor, userTypeIds, preferredLanguageId, actorUserId, chunk, schemeIdCache);
                    uploaded += res.uploadedRows();
                    skipped += res.skippedRows();
                    chunk = new ArrayList<>(CHUNK_SIZE);
                }
            }

            if (!chunk.isEmpty()) {
                var res = chunkProcessor.processChunk(schemaName, tenantCode, actor, userTypeIds, preferredLanguageId, actorUserId, chunk, schemeIdCache);
                uploaded += res.uploadedRows();
                skipped += res.skippedRows();
            }

            return new ProcessResult(uploaded, skipped);
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BadRequestException("Failed to read uploaded file", List.of(
                    UploadErrorDTO.builder().row(0).field("file").message("Unable to read file content").build()
            ));
        }
    }

    private ProcessResult processXlsx(
            String schemaName,
            String tenantCode,
            TenantUserRecord actor,
            int actorUserId,
            Map<String, Integer> userTypeIds,
            int preferredLanguageId,
            MultipartFile file,
            Map<String, Integer> schemeIdCache
    ) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return new ProcessResult(0, 0);
            }

            int firstRowIndex = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRowIndex);
            if (headerRow == null) {
                return new ProcessResult(0, 0);
            }

            List<String> rawHeaders = new ArrayList<>();
            int headerSize = Math.max(headerRow.getLastCellNum(), (short) 0);
            for (int i = 0; i < headerSize; i++) {
                rawHeaders.add(DATA_FORMATTER.formatCellValue(headerRow.getCell(i)));
            }
            List<String> activeHeaders = resolveHeaderVariant(firstRowIndex + 1, rawHeaders);

            int uploaded = 0;
            int skipped = 0;
            List<PumpOperatorUploadChunkProcessor.UploadRow> chunk = new ArrayList<>(CHUNK_SIZE);
            for (int i = firstRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                Map<String, String> map = toRowMap(activeHeaders, row);
                if (isAllBlank(map)) {
                    continue;
                }
                chunk.add(toUploadRow(i + 1, map));
                if (chunk.size() >= CHUNK_SIZE) {
                    var res = chunkProcessor.processChunk(schemaName, tenantCode, actor, userTypeIds, preferredLanguageId, actorUserId, chunk, schemeIdCache);
                    uploaded += res.uploadedRows();
                    skipped += res.skippedRows();
                    chunk = new ArrayList<>(CHUNK_SIZE);
                }
            }
            if (!chunk.isEmpty()) {
                var res = chunkProcessor.processChunk(schemaName, tenantCode, actor, userTypeIds, preferredLanguageId, actorUserId, chunk, schemeIdCache);
                uploaded += res.uploadedRows();
                skipped += res.skippedRows();
            }

            return new ProcessResult(uploaded, skipped);
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Failed to read uploaded file", List.of(
                    UploadErrorDTO.builder().row(0).field("file").message("Unable to read file content").build()
            ));
        }
    }

    private void validateRow(
            String schemaName,
            PumpOperatorUploadChunkProcessor.UploadRow row,
            Map<String, Integer> schemeIdCache,
            LongHashSet seenPhones,
            Map<String, Boolean> phoneExistsCache,
            List<UploadErrorDTO> errors
    ) {
        if (row.fullName().isBlank()) {
            errors.add(err(row.rowNumber(), "full_name", "Full name is required"));
            return;
        }

        if (row.phone().isBlank()) {
            errors.add(err(row.rowNumber(), "phone_number", "Phone number is required"));
            return;
        }
        if (row.phone().startsWith("+")) {
            errors.add(err(row.rowNumber(), "phone_number", "Phone number must not start with '+'"));
            return;
        }
        if (!PhoneNumberUtil.isValidIndianMobile(row.phone())) {
            errors.add(err(row.rowNumber(), "phone_number", "Phone number must be a valid 10-digit Indian number (no country code)"));
            return;
        }
        try {
            long phoneNum = Long.parseLong(row.phone());
            if (!seenPhones.add(phoneNum)) {
                errors.add(err(row.rowNumber(), "phone_number", "Duplicate phone_number in uploaded file"));
                return;
            }
        } catch (NumberFormatException nfe) {
            errors.add(err(row.rowNumber(), "phone_number", "Phone number must be numeric"));
            return;
        }

        String normalizedPhone = PhoneNumberUtil.normalizeIndianMobileForDb(row.phone());
        Boolean exists = phoneExistsCache.get(normalizedPhone);
        if (exists == null) {
            Integer userId = userUploadRepository.findUserIdByEmailOrPhone(schemaName, null, normalizedPhone);
            exists = userId != null;
            phoneExistsCache.put(normalizedPhone, exists);
        }
        if (Boolean.TRUE.equals(exists)) {
            errors.add(err(row.rowNumber(), "phone_number", "Duplicate phone_number already exists"));
            return;
        }

        if (row.personType().isBlank()) {
            errors.add(err(row.rowNumber(), "person_type", "person_type is required"));
            return;
        }
        if (!ALLOWED_USER_TYPES.contains(row.personType())) {
            errors.add(err(row.rowNumber(), "person_type", "person_type must be one of: " + String.join(", ", ALLOWED_USER_TYPES)));
            return;
        }

        if (row.stateSchemeId().isBlank()) {
            errors.add(err(row.rowNumber(), "state_scheme_id", "state_scheme_id is required"));
            return;
        }

        String schemeKey = row.stateSchemeId();
        Integer schemeId = schemeIdCache.get(schemeKey);
        if (schemeId == null) {
            Integer found = userUploadRepository.findSchemeId(schemaName, blankToNull(row.stateSchemeId()), null);
            schemeId = found != null ? found : -1;
            schemeIdCache.put(schemeKey, schemeId);
        }
        if (schemeId < 0) {
            errors.add(err(row.rowNumber(), "state_scheme_id", "Invalid state_scheme_id (scheme not found)"));
        }
    }

    private Map<String, String> toRowMap(List<String> activeHeaders, CSVRecord record) {
        List<String> values = new ArrayList<>(activeHeaders.size());
        for (int c = 0; c < activeHeaders.size(); c++) {
            values.add(c < record.size() ? normalizeValue(record.get(c)) : "");
        }
        return toMap(activeHeaders, values);
    }

    private Map<String, String> toRowMap(List<String> activeHeaders, Row row) {
        List<String> values = new ArrayList<>(activeHeaders.size());
        for (int c = 0; c < activeHeaders.size(); c++) {
            values.add(row == null ? "" : normalizeValue(DATA_FORMATTER.formatCellValue(row.getCell(c))));
        }
        return toMap(activeHeaders, values);
    }

    private PumpOperatorUploadChunkProcessor.UploadRow toUploadRow(int rowNumber, Map<String, String> v) {
        String personTypeField = v.containsKey("person_type") ? "person_type" : "person_type_id";
        String personTypeInFile = normalizeHeader(safe(v.get(personTypeField)));
        return new PumpOperatorUploadChunkProcessor.UploadRow(
                rowNumber,
                normalizeValue(v.get("first_name")),
                normalizeValue(v.get("last_name")),
                normalizeValue(v.get("full_name")),
                normalizeValue(v.get("phone_number")),
                personTypeInFile,
                normalizeValue(v.get("state_scheme_id"))
        );
    }

    private record ProcessResult(int uploadedRows, int skippedRows) {}

    private String toTenantCode(String schemaName) {
        if (schemaName == null) {
            return null;
        }
        String s = schemaName.trim();
        if (s.startsWith("tenant_") && s.length() > "tenant_".length()) {
            return s.substring("tenant_".length()).toUpperCase(Locale.ROOT);
        }
        return s.toUpperCase(Locale.ROOT);
    }

    private UploadErrorDTO err(int row, String field, String message) {
        return UploadErrorDTO.builder().row(row).field(field).message(message).build();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private Map<String, Integer> resolveUserTypeIds() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : ALLOWED_USER_TYPES) {
            String cName = key.toUpperCase(Locale.ROOT);
            Integer id = userCommonRepository.findUserTypeIdByName(cName)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Missing required user type in common_schema.user_type_master_table: " + cName
                    ));
            out.put(key, id);
        }
        return out;
    }

    private static boolean isAllBlank(Map<String, String> map) {
        for (String v : map.values()) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> toMap(List<String> headers, List<String> values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.put(headers.get(i), i < values.size() ? normalizeValue(values.get(i)) : "");
        }
        return map;
    }

    private List<String> resolveHeaderVariant(int rowNum, List<String> rawHeaders) {
        List<String> actual = rawHeaders.stream()
                .map(v -> v == null ? "" : v.trim())
                .toList();

        for (List<String> variant : ALLOWED_HEADER_VARIANTS) {
            if (actual.equals(variant)) {
                return variant;
            }
        }

        String expected = ALLOWED_HEADER_VARIANTS.stream()
                .map(v -> String.join(",", v))
                .reduce((a, b) -> a + " OR " + b)
                .orElse("");

        throw new BadRequestException("Invalid headers", List.of(
                UploadErrorDTO.builder()
                        .row(rowNum)
                        .field("header")
                        .message("Header row must be exactly: " + expected)
                        .build()
        ));
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.replace(' ', '_');
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    // (old ParsedRow/ValidationResult removed; uploads are streamed + processed in chunks)
}
