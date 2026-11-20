package org.arghyam.jalsoochak.tenant.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.arghyam.jalsoochak.tenant.dto.internal.SituationReportLayoutConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.DateFormatsDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.NumberFormatDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportBlockDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportSettingsDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.RoleLayoutDTO;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportKind;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportRole;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Write-time validation of a {@code SITUATION_REPORT_LAYOUT} config value. Strict, because
 * message-service reads the stored value for every report event: a mistake caught here is a 400
 * while someone is looking at it, rather than a report that silently falls back to the built-in
 * layout at the next run.
 *
 * <p>Lists every problem at once, each as {@code path: message}, in one
 * {@link InvalidConfigValueException}, so a long layout can be fixed in one pass.</p>
 */
@Component
public class SituationReportLayoutValidator {

    static final int SUPPORTED_VERSION = 1;
    static final int MAX_STORED_BYTES = 64 * 1024;
    static final int MAX_URL_LENGTH = 300;
    static final int MAX_DATE_PATTERN_LENGTH = 40;
    static final Pattern SUPPORT_PHONE = Pattern.compile("^[0-9+()\\- ]{3,20}$");
    /** Enough to show the pattern of a mistake; each one costs a re-parse. */
    static final int MAX_UNKNOWN_PROPERTIES_REPORTED = 50;

    /** A date with the longest English month name, to show what a pattern prints. */
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2026, 9, 30);
    private static final LocalTime SAMPLE_TIME = LocalTime.of(16, 0);

    private final ObjectMapper objectMapper;

    public SituationReportLayoutValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param raw    the value as sent, to find properties the bound {@code config} silently dropped
     * @param config the value bound from {@code raw}
     * @throws InvalidConfigValueException listing every problem found
     */
    public void validate(JsonNode raw, SituationReportLayoutConfigDTO config) {
        List<String> problems = problems(raw, config);
        if (!problems.isEmpty()) {
            throw new InvalidConfigValueException("SITUATION_REPORT_LAYOUT has " + problems.size()
                    + (problems.size() == 1 ? " problem: " : " problems: ") + String.join("; ", problems));
        }
    }

    /** Every problem, each as {@code path: message}, in document order within each check. */
    List<String> problems(JsonNode raw, SituationReportLayoutConfigDTO config) {
        List<String> problems = new ArrayList<>(unknownProperties(raw));

        if (config.version() == null) {
            problems.add("version: is required");
        } else if (config.version() != SUPPORTED_VERSION) {
            problems.add("version: must be " + SUPPORTED_VERSION);
        }
        storedSize(config, problems);
        if (config.settings() != null) {
            settings(config.settings(), problems);
        }
        block(ReportKind.DAILY, "daily", config.daily(), problems);
        block(ReportKind.WEEKLY, "weekly", config.weekly(), problems);
        return problems;
    }

    /**
     * The properties the document has but the DTO does not declare, each with its path. Spring Boot's
     * mapper drops them silently, and the stored value is re-serialised from the DTO, so a misspelt
     * property would otherwise vanish without a word.
     *
     * <p>A strict parse stops at the first one, so each is removed from a copy and the parse repeated.
     * The path comes from the exception, which Jackson builds as it unwinds; the parser's own context
     * can't be used, because Jackson replays a record's unknown properties after leaving the record.</p>
     */
    private List<String> unknownProperties(JsonNode raw) {
        ObjectMapper strict = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        JsonNode remaining = raw.deepCopy();
        List<String> unknown = new ArrayList<>();
        while (unknown.size() < MAX_UNKNOWN_PROPERTIES_REPORTED) {
            try {
                strict.treeToValue(remaining, SituationReportLayoutConfigDTO.class);
                return unknown;
            } catch (UnrecognizedPropertyException e) {
                unknown.add(path(e.getPath()) + ": unknown property");
                if (!remove(remaining, e.getPath())) {
                    return unknown;
                }
            } catch (JsonProcessingException e) {
                // Not expected: the caller bound the same tree with every other check Jackson makes.
                throw new InvalidConfigValueException(
                        "SITUATION_REPORT_LAYOUT can't be read: " + e.getOriginalMessage(), e);
            }
        }
        return unknown;
    }

    /** {@code daily.roles.SECTION_OFFICER.layout.header[0].size} */
    private static String path(List<JsonMappingException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            } else {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** Removes the property at {@code references} from {@code root}; false if it isn't there. */
    private static boolean remove(JsonNode root, List<JsonMappingException.Reference> references) {
        if (references.isEmpty()) {
            return false;
        }
        JsonNode parent = root;
        for (JsonMappingException.Reference reference : references.subList(0, references.size() - 1)) {
            parent = reference.getFieldName() != null ? parent.get(reference.getFieldName())
                    : parent.get(reference.getIndex());
            if (parent == null) {
                return false;
            }
        }
        String property = references.get(references.size() - 1).getFieldName();
        return parent instanceof ObjectNode object && property != null && object.remove(property) != null;
    }

    private void storedSize(SituationReportLayoutConfigDTO config, List<String> problems) {
        int bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(config).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SITUATION_REPORT_LAYOUT can't be serialised", e);
        }
        if (bytes > MAX_STORED_BYTES) {
            problems.add("(document): must be at most " + MAX_STORED_BYTES + " bytes as stored, but is " + bytes);
        }
    }

    private void settings(ReportSettingsDTO settings, List<String> problems) {
        if (settings.dashboardUrl() != null && !isHttpsUrl(settings.dashboardUrl())) {
            problems.add("settings.dashboardUrl: must be an absolute https:// URL with a host, at most "
                    + MAX_URL_LENGTH + " characters");
        }
        if (settings.supportPhone() != null && !isSupportPhone(settings.supportPhone())) {
            problems.add("settings.supportPhone: must be 3 to 20 digits, spaces and + ( ) -");
        }
        DateFormatsDTO formats = settings.dateFormats();
        if (formats != null) {
            datePattern("settings.dateFormats.date", formats.date(), SAMPLE_DATE, problems);
            datePattern("settings.dateFormats.shortDate", formats.shortDate(), SAMPLE_DATE, problems);
            datePattern("settings.dateFormats.time", formats.time(), SAMPLE_TIME, problems);
        }
        NumberFormatDTO numbers = settings.numberFormat();
        if (numbers != null && numbers.decimals() != null && (numbers.decimals() < 0 || numbers.decimals() > 1)) {
            problems.add("settings.numberFormat.decimals: must be 0 or 1");
        }
    }

    /** The URL becomes a PDF link annotation, so only https: no javascript:, no plain http. */
    private static boolean isHttpsUrl(String url) {
        if (url.length() > MAX_URL_LENGTH || !url.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isSupportPhone(String phone) {
        return SUPPORT_PHONE.matcher(phone).matches() && phone.chars().anyMatch(Character::isDigit);
    }

    /** The pattern must format {@code sample} under {@code Locale.ENGLISH} into printable text. */
    private static void datePattern(String path, String pattern, TemporalAccessor sample, List<String> problems) {
        if (pattern == null) {
            return;
        }
        if (pattern.isBlank()) {
            problems.add(path + ": must not be blank; leave it out for the built-in format");
            return;
        }
        if (pattern.length() > MAX_DATE_PATTERN_LENGTH) {
            problems.add(path + ": must be at most " + MAX_DATE_PATTERN_LENGTH + " characters");
            return;
        }
        String formatted;
        try {
            formatted = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(sample);
        } catch (IllegalArgumentException e) {
            problems.add(path + ": is not a valid date-time pattern: " + e.getMessage());
            return;
        } catch (DateTimeException e) {
            problems.add(path + ": can't format a " + (sample instanceof LocalDate ? "date" : "time")
                    + " (" + e.getMessage() + ")");
            return;
        }
        ReportLayoutRules.firstUnprintable(formatted).ifPresent(c -> problems.add(path + ": prints "
                + ReportLayoutRules.describe(c) + ", which can't be printed in a report"));
    }

    private static void block(ReportKind kind, String path, ReportBlockDTO block, List<String> problems) {
        if (block == null) {
            return;
        }
        if (block.roles() == null) {
            problems.add(path + ".roles: is required; use {} to send the report to nobody");
            return;
        }
        for (Map.Entry<ReportRole, RoleLayoutDTO> entry : block.roles().entrySet()) {
            ReportRole role = entry.getKey();
            String rolePath = path + ".roles." + role;
            RoleLayoutDTO roleLayout = entry.getValue();
            boolean useDefault = roleLayout != null && Boolean.TRUE.equals(roleLayout.useDefault());
            boolean hasLayout = roleLayout != null && roleLayout.layout() != null;
            if (useDefault == hasLayout) {
                problems.add(rolePath + ": must set exactly one of \"useDefault\": true or a \"layout\"");
            } else if (useDefault && !kind.hasBuiltInLayout(role)) {
                problems.add(rolePath + ".useDefault: there is no built-in " + kind + " layout for " + role
                        + "; give a layout");
            } else if (hasLayout) {
                ReportLayoutRules.check(kind, role, rolePath + ".layout", roleLayout.layout(), problems);
            }
        }
    }
}
