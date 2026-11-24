package org.arghyam.jalsoochak.message.report.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {name}} placeholders a layout's text may use. Substitution is plain text: no
 * expressions and no escaping.
 */
public enum Placeholder {
    /** The officer's resolved name, or {@code Officer}. */
    OFFICER_NAME("officerName", ReportKind.DAILY, ReportKind.WEEKLY),
    /** Today in IST, in the {@code date} format. */
    GENERATED_DATE("generatedDate", ReportKind.DAILY, ReportKind.WEEKLY),
    /** The day the daily report covers, in the {@code date} format. */
    REPORT_DATE("reportDate", ReportKind.DAILY),
    /** The time the daily window closed, in the {@code time} format. */
    CUTOFF("cutoff", ReportKind.DAILY),
    WEEK_START("weekStart", ReportKind.WEEKLY),
    WEEK_END("weekEnd", ReportKind.WEEKLY),
    PREVIOUS_WEEK_START("previousWeekStart", ReportKind.WEEKLY),
    PREVIOUS_WEEK_END("previousWeekEnd", ReportKind.WEEKLY),
    /** The English weekday name, e.g. {@code Thursday}. */
    WEEK_START_DAY_NAME("weekStartDayName", ReportKind.WEEKLY),
    WEEK_END_DAY_NAME("weekEndDayName", ReportKind.WEEKLY),
    /** The LPCD threshold analytics applied to the low-LPCD list. */
    LOW_LPCD_THRESHOLD("lowLpcdThreshold", ReportKind.WEEKLY),
    /** The supply-days bar analytics applied to "Schemes Supplying Water". */
    MIN_SUPPLY_DAYS("minSupplyDays", ReportKind.WEEKLY),
    /** Allowed only in a {@code SUPPORT_CONTACT} footer, which must contain it. */
    SUPPORT_PHONE("supportPhone", ReportKind.DAILY, ReportKind.WEEKLY);

    /** One well-formed placeholder; group 1 is its name. */
    public static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)}");

    private final String key;
    private final Set<ReportKind> kinds;

    Placeholder(String key, ReportKind... kinds) {
        this.key = key;
        this.kinds = EnumSet.copyOf(Arrays.asList(kinds));
    }

    /** The name written between the braces, e.g. {@code officerName}. */
    public String key() {
        return key;
    }

    /** The placeholder as written in a layout, e.g. {@code {officerName}}. */
    public String token() {
        return "{" + key + "}";
    }

    public boolean availableIn(ReportKind kind) {
        return kinds.contains(kind);
    }

    public static Optional<Placeholder> byKey(String key) {
        for (Placeholder placeholder : values()) {
            if (placeholder.key.equals(key)) {
                return Optional.of(placeholder);
            }
        }
        return Optional.empty();
    }

    /** The names of the well-formed placeholders in {@code text}, in order of appearance. */
    public static List<String> keysIn(String text) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /**
     * Whether {@code text} has a brace that is not part of a well-formed placeholder: an unmatched
     * opening or closing brace, a nested pair, or a name that is not plain letters.
     */
    public static boolean hasMalformedBraces(String text) {
        String rest = TOKEN.matcher(text).replaceAll("");
        return rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0;
    }
}
