package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.dto.TenantRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalises the tenant halves carried on a notification event into one {@link TenantRef},
 * filling in whichever half the producer did not have in scope from
 * {@code common_schema.tenant_master_table}.
 *
 * <p>The id ↔ state-code mapping never changes for a live tenant, so successful lookups are
 * cached forever in memory; the map is bounded by the number of tenants. Misses are not cached,
 * because a tenant created after this instance started must resolve on its next event.
 *
 * <p>A lookup failure is never fatal: the partially-resolved reference is returned and the send
 * continues, which leaves it on the system default provider rather than dropping the message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantRefResolver {

    private final JdbcTemplate jdbcTemplate;

    private final Map<Integer, String> codeById = new ConcurrentHashMap<>();
    private final Map<String, Integer> idByCode = new ConcurrentHashMap<>();

    /**
     * @param id   tenant id as carried on the event, or null
     * @param code tenant state code as carried on the event, or null
     * @return both halves where they could be resolved, or {@link TenantRef#NONE} when the event
     *         carries no tenant at all
     */
    public TenantRef resolve(Integer id, String code) {
        TenantRef ref = new TenantRef(id, code);
        if (!ref.isPresent()) {
            return TenantRef.NONE;
        }
        if (ref.isComplete()) {
            remember(ref.id(), ref.code());
            return ref;
        }
        return ref.id() != null
                ? new TenantRef(ref.id(), codeFor(ref.id()))
                : new TenantRef(idFor(ref.code()), ref.code());
    }

    private String codeFor(int id) {
        String cached = codeById.get(id);
        if (cached != null) {
            return cached;
        }
        String code = queryOne(
                "SELECT state_code FROM common_schema.tenant_master_table"
                        + " WHERE id = ? AND deleted_at IS NULL LIMIT 1",
                (rs, n) -> rs.getString("state_code"), id);
        if (code == null) {
            log.warn("[TenantRef] No live tenant for id={}, falling back to the system default", id);
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        remember(id, normalized);
        return normalized;
    }

    private Integer idFor(String code) {
        Integer cached = idByCode.get(code);
        if (cached != null) {
            return cached;
        }
        Integer id = queryOne(
                "SELECT id FROM common_schema.tenant_master_table"
                        + " WHERE UPPER(state_code) = ? AND deleted_at IS NULL LIMIT 1",
                (rs, n) -> rs.getInt("id"), code);
        if (id == null) {
            log.warn("[TenantRef] No live tenant for code={}, falling back to the system default", code);
            return null;
        }
        remember(id, code);
        return id;
    }

    private void remember(int id, String code) {
        codeById.putIfAbsent(id, code);
        idByCode.putIfAbsent(code, id);
    }

    /**
     * Returns the single column value, or null when the tenant is unknown or the query fails.
     * A resolution failure must not stop a notification, so the exception is logged and swallowed.
     */
    private <T> T queryOne(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object arg) {
        try {
            List<T> rows = jdbcTemplate.query(sql, mapper, arg);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException e) {
            log.error("[TenantRef] Tenant lookup failed, falling back to the system default: {}",
                    e.getMessage());
            return null;
        }
    }
}
