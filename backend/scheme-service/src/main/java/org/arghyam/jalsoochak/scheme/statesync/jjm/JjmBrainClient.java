package org.arghyam.jalsoochak.scheme.statesync.jjm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataException;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link StateMasterDataSource} over the JJM Brain Arghyam integration API (Assam), v1.
 *
 * <p>Defensive by design, because the published samples disagree with each other:
 * <ul>
 *   <li><b>Paging</b> walks {@code ?page=N} and stops on an empty page, a short page or
 *       {@code meta.last_page}. It never follows {@code links.next}: two endpoints document links
 *       pointing at {@code http://127.0.0.1:8000}, and two return them empty. {@code meta.total} is
 *       not trusted as a stop condition (a sample reports {@code total: 1} beside two rows).</li>
 *   <li><b>De-duplication</b> by code, since offset paging over live data can repeat a row.</li>
 *   <li><b>Types</b>: ids, counts and coordinates are read whether they arrive as numbers or strings,
 *       and both {@code centre_scheme_id} spellings are accepted.</li>
 *   <li><b>Parents</b> are read from the nested object ({@code "division": {"code": …}}) or a flat
 *       {@code <parent>_code} field.</li>
 * </ul>
 * Requests are spaced by {@code min-request-interval}, and 429 / 5xx / I/O failures are retried with
 * exponential back-off (honouring {@code Retry-After}). 401 and 403 fail at once.
 */
@Slf4j
public class JjmBrainClient implements StateMasterDataSource {

    private static final String PREFIX = "/arghyam/";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final DateTimeFormatter UPSTREAM_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Scheme-level officer arrays and the role each implies when an entry carries none. */
    private static final Map<String, String> OFFICER_ARRAYS = Map.of(
            "section-officer", "section-officer",
            "executive-engineer", "executive-engineer",
            "sdo", "sdo",
            "pump-operator", "jal-mitra");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final StateSyncProperties.Jjm settings;
    private final Sleeper sleeper;
    private long lastRequestAtNanos;

    /** Indirection over {@link Thread#sleep} so tests run without real delays. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public JjmBrainClient(StateSyncProperties.Jjm settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, d -> Thread.sleep(d.toMillis()));
    }

    public JjmBrainClient(StateSyncProperties.Jjm settings, ObjectMapper objectMapper, Sleeper sleeper) {
        if (settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            throw new IllegalStateException("state-sync.jjm.api-key (JJM_BRAIN_API_KEY) is required when state sync is enabled");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) settings.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) settings.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(settings.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("x-api-key", settings.getApiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.sleeper = sleeper;
    }

    // ── Masters ─────────────────────────────────────────────────────────────

    @Override
    public List<UpstreamNode> zones() {
        return nodes("zone-master", null);
    }

    @Override
    public List<UpstreamNode> circles() {
        return nodes("circle-master", "zone");
    }

    @Override
    public List<UpstreamNode> divisions() {
        // A division nests both its zone and its circle; the circle is the immediate parent.
        return nodes("division-master", "circle");
    }

    @Override
    public List<UpstreamNode> subdivisions() {
        return nodes("subdivision-master", "division");
    }

    @Override
    public List<UpstreamNode> districts() {
        return nodes("district-master", null);
    }

    @Override
    public List<UpstreamNode> blocks() {
        return nodes("block-master", "district");
    }

    @Override
    public List<UpstreamNode> panchayats() {
        return nodes("panchayat-master", "block");
    }

    @Override
    public List<UpstreamNode> villages() {
        return nodes("village-master", "panchayat");
    }

    @Override
    public List<UpstreamPerson> users() {
        return pagedDistinct("user-master", Map.of(), JjmBrainClient::person, UpstreamPerson::code);
    }

    // ── Schemes ─────────────────────────────────────────────────────────────

    @Override
    public List<UpstreamScheme> schemes(LocalDateTime updatedSince) {
        Map<String, String> params = updatedSince == null
                ? Map.of()
                : Map.of("updated_since", updatedSince.format(UPSTREAM_DATE_TIME));
        return pagedDistinct("schemes", params, JjmBrainClient::scheme, UpstreamScheme::code);
    }

    @Override
    public Optional<UpstreamScheme> schemeByCode(String code) {
        return pagedDistinct("schemes", Map.of("code", code), JjmBrainClient::scheme, UpstreamScheme::code)
                .stream().filter(s -> code.equals(s.code())).findFirst();
    }

    @Override
    public Optional<UpstreamScheme> schemeByCentreSchemeId(String centreSchemeId) {
        return pagedDistinct("schemes", Map.of("centre_scheme_id", centreSchemeId), JjmBrainClient::scheme,
                UpstreamScheme::code)
                .stream().filter(s -> centreSchemeId.equals(s.centreSchemeId())).findFirst();
    }

    @Override
    public List<String> archivedSchemeCodes() {
        return codeList("archived-schemes");
    }

    @Override
    public List<String> blockedUserCodes() {
        return codeList("blocked-users");
    }

    // ── Paging ──────────────────────────────────────────────────────────────

    private List<UpstreamNode> nodes(String endpoint, String parentKey) {
        return pagedDistinct(endpoint, Map.of(), row -> node(row, parentKey), UpstreamNode::code);
    }

    private <T> List<T> pagedDistinct(String endpoint, Map<String, String> params,
                                      Function<JsonNode, T> mapper, Function<T, String> codeOf) {
        Map<String, T> byCode = new LinkedHashMap<>();
        int duplicates = 0;
        for (int page = 1; ; page++) {
            if (page > settings.getMaxPages()) {
                throw new StateMasterDataException(endpoint + ": still returning rows after "
                        + settings.getMaxPages() + " pages; aborting the crawl");
            }
            JsonNode root = get(endpoint, params, page);
            JsonNode data = root.path("data");
            JsonNode rows = data.path("data");
            if (!rows.isArray()) {
                throw new StateMasterDataException(endpoint + ": response has no data.data array");
            }
            for (JsonNode row : rows) {
                T item = mapper.apply(row);
                String code = codeOf.apply(item);
                if (code == null || code.isBlank()) {
                    continue;
                }
                if (byCode.putIfAbsent(code, item) != null) {
                    duplicates++;
                }
            }
            JsonNode meta = data.path("meta");
            int perPage = meta.path("per_page").asInt(DEFAULT_PAGE_SIZE);
            Integer lastPage = meta.hasNonNull("last_page") ? meta.get("last_page").asInt() : null;
            if (rows.isEmpty() || rows.size() < perPage || (lastPage != null && page >= lastPage)) {
                break;
            }
        }
        if (duplicates > 0) {
            log.warn("[state-sync] {}: {} row(s) repeated across pages were de-duplicated", endpoint, duplicates);
        }
        return new ArrayList<>(byCode.values());
    }

    private List<String> codeList(String endpoint) {
        JsonNode data = get(endpoint, Map.of(), null).path("data");
        if (!data.isArray()) {
            throw new StateMasterDataException(endpoint + ": response data is not an array of codes");
        }
        Set<String> codes = new LinkedHashSet<>();
        data.forEach(n -> {
            String code = n.asText(null);
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        });
        return new ArrayList<>(codes);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private JsonNode get(String endpoint, Map<String, String> params, Integer page) {
        Function<UriBuilder, URI> uri = b -> {
            UriBuilder builder = b.path(PREFIX + endpoint);
            params.forEach(builder::queryParam);
            if (page != null) {
                builder.queryParam("page", page);
            }
            return builder.build();
        };
        Duration backoff = settings.getInitialBackoff();
        for (int attempt = 1; ; attempt++) {
            throttle();
            try {
                String body = restClient.get().uri(uri).retrieve().body(String.class);
                return objectMapper.readTree(body == null ? "{}" : body);
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    throw new StateMasterDataException(endpoint + ": upstream rejected the API key (HTTP "
                            + status.value() + ")", e);
                }
                boolean retryable = status.value() == 429 || status.is5xxServerError();
                if (!retryable || attempt >= settings.getMaxAttempts()) {
                    throw new StateMasterDataException(endpoint + ": HTTP " + status.value()
                            + " after " + attempt + " attempt(s)", e);
                }
                Duration wait = retryAfter(e).orElse(backoff);
                log.warn("[state-sync] {} page={} HTTP {} — retrying in {}s (attempt {}/{})",
                        endpoint, page, status.value(), wait.toSeconds(), attempt, settings.getMaxAttempts());
                pause(wait);
            } catch (ResourceAccessException e) {
                if (attempt >= settings.getMaxAttempts()) {
                    throw new StateMasterDataException(endpoint + ": I/O failure after " + attempt + " attempt(s)", e);
                }
                log.warn("[state-sync] {} page={} I/O failure — retrying in {}s (attempt {}/{})",
                        endpoint, page, backoff.toSeconds(), attempt, settings.getMaxAttempts());
                pause(backoff);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new StateMasterDataException(endpoint + ": response is not JSON", e);
            }
            backoff = backoff.multipliedBy(2);
        }
    }

    private static Optional<Duration> retryAfter(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Math.min(300, Long.parseLong(value.trim()))));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private synchronized void throttle() {
        long gapNanos = settings.getMinRequestInterval().toNanos();
        long waitNanos = lastRequestAtNanos + gapNanos - System.nanoTime();
        if (lastRequestAtNanos != 0 && waitNanos > 0) {
            pause(Duration.ofNanos(waitNanos));
        }
        lastRequestAtNanos = System.nanoTime();
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StateMasterDataException("interrupted while waiting to call the upstream", e);
        }
    }

    // ── Row mapping ─────────────────────────────────────────────────────────

    static UpstreamNode node(JsonNode row, String parentKey) {
        String parentCode = null;
        if (parentKey != null) {
            parentCode = text(row.path(parentKey).path("code"));
            if (parentCode == null) {
                parentCode = text(row.path(parentKey + "_code"));
            }
        }
        return new UpstreamNode(text(row.path("code")), text(row.path("name")), parentCode);
    }

    static UpstreamPerson person(JsonNode row) {
        return new UpstreamPerson(text(row.path("code")), text(row.path("name")),
                text(row.path("phone")), text(row.path("role")));
    }

    static UpstreamScheme scheme(JsonNode row) {
        String centre = text(row.path("centre_scheme_id"));
        if (centre == null) {
            centre = text(row.path("center_scheme_id"));
        }

        List<String> subdivisionCodes = new ArrayList<>();
        Map<String, UpstreamPerson> officers = new LinkedHashMap<>();
        for (JsonNode sub : row.path("subdivisions")) {
            addIfPresent(subdivisionCodes, text(sub.path("code")));
            addOfficers(officers, sub.path("sdos"), "sdo");
        }
        addOfficers(officers, row.path("division").path("executive_engineers"), "executive-engineer");
        OFFICER_ARRAYS.forEach((key, role) -> addOfficers(officers, row.path(key), role));

        List<String> villageCodes = new ArrayList<>();
        for (JsonNode village : row.path("village")) {
            addIfPresent(villageCodes, text(village.path("code")));
        }

        return new UpstreamScheme(
                text(row.path("code")),
                centre,
                text(row.path("state_scheme_id")),
                text(row.path("name")),
                text(row.path("work_status")),
                text(row.path("operating_status")),
                integer(row.path("planned_fhtc_imis")),
                integer(row.path("achieved_fhtc_imis")),
                text(row.path("latitude")),
                text(row.path("longitude")),
                subdivisionCodes,
                villageCodes,
                new ArrayList<>(officers.values()),
                timestamp(text(row.path("updated_at"))));
    }

    private static void addOfficers(Map<String, UpstreamPerson> into, JsonNode array, String impliedRole) {
        for (JsonNode entry : array) {
            UpstreamPerson person = person(entry);
            if (person.code() == null) {
                continue;
            }
            if (person.role() == null) {
                person = new UpstreamPerson(person.code(), person.name(), person.phone(), impliedRole);
            }
            into.putIfAbsent(person.code(), person);
        }
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !list.contains(value)) {
            list.add(value);
        }
    }

    /** Text of a scalar node, numbers included; {@code null} for missing, null or blank. */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String value = node.isNumber() ? node.numberValue().toString() : node.asText();
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    static Integer integer(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Upstream timestamps come as {@code yyyy-MM-dd HH:mm:ss} with no zone. An ISO value with an offset
     * is accepted too (the fix we asked for) and converted to IST wall time.
     */
    static LocalDateTime timestamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, UPSTREAM_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(IST).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
