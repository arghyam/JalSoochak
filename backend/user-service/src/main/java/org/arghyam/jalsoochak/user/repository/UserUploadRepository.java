package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class UserUploadRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService pii;
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]*$");

    public Integer findUserIdByEmailOrPhone(String schemaName, String email, String phone) {
        validateSchemaName(schemaName);
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            return null;
        }

        // Keep SQL type resolution simple for Postgres prepared statements:
        // run a deterministic query based on which identifier is present.
        String trimmedEmail = email != null ? email.trim() : null;
        if (trimmedEmail != null && !trimmedEmail.isBlank()) {
            String byEmail = String.format("""
                    SELECT id
                    FROM %s.user_table
                    WHERE deleted_at IS NULL
                      AND lower(email) = lower(?)
                    LIMIT 1
                    """, schemaName);
            try {
                return jdbcTemplate.queryForObject(byEmail, Integer.class, trimmedEmail);
            } catch (EmptyResultDataAccessException ignored) {
                // fallback to phone if provided
            }
        }

        String trimmedPhone = phone != null ? phone.trim() : null;
        if (trimmedPhone != null && !trimmedPhone.isBlank()) {
            // Lookup via HMAC hash — the encrypted column cannot be searched directly
            String byPhone = String.format("""
                    SELECT id
                    FROM %s.user_table
                    WHERE deleted_at IS NULL
                      AND phone_number_hash = ?
                    LIMIT 1
                    """, schemaName);
            try {
                return jdbcTemplate.queryForObject(byPhone, Integer.class, pii.hmac(trimmedPhone));
            } catch (EmptyResultDataAccessException ignored) {
                return null;
            }
        }

        return null;
    }

    public boolean isUserStateAdmin(String schemaName, Integer userId) {
        validateSchemaName(schemaName);
        if (userId == null) {
            return false;
        }

        String sql = String.format("""
                SELECT EXISTS (
                    SELECT 1
                    FROM %s.user_table u
                    WHERE u.id = ?
                      AND u.deleted_at IS NULL
                      AND u.user_type = (
                          SELECT ut.id
                          FROM common_schema.user_type_master_table ut
                          WHERE lower(ut.c_name) = lower('STATE_ADMIN')
                          LIMIT 1
                      )
                )
                """, schemaName);

        Boolean ok = jdbcTemplate.queryForObject(sql, Boolean.class, userId);
        return Boolean.TRUE.equals(ok);
    }

    public Integer findSchemeId(String schemaName, String stateSchemeId, String centreSchemeId) {
        validateSchemaName(schemaName);

        boolean hasState = stateSchemeId != null && !stateSchemeId.isBlank();
        boolean hasCentre = centreSchemeId != null && !centreSchemeId.isBlank();
        if (!hasState && !hasCentre) {
            return null;
        }

        String sql;
        Object[] args;
        if (hasState && hasCentre) {
            sql = String.format("""
                    SELECT id
                    FROM %s.scheme_master_table
                    WHERE deleted_at IS NULL
                      AND state_scheme_id = ?
                      AND centre_scheme_id = ?
                    LIMIT 1
                    """, schemaName);
            args = new Object[]{stateSchemeId, centreSchemeId};
        } else if (hasState) {
            sql = String.format("""
                    SELECT id
                    FROM %s.scheme_master_table
                    WHERE deleted_at IS NULL
                      AND state_scheme_id = ?
                    LIMIT 1
                    """, schemaName);
            args = new Object[]{stateSchemeId};
        } else {
            sql = String.format("""
                    SELECT id
                    FROM %s.scheme_master_table
                    WHERE deleted_at IS NULL
                      AND centre_scheme_id = ?
                    LIMIT 1
                    """, schemaName);
            args = new Object[]{centreSchemeId};
        }

        try {
            return jdbcTemplate.queryForObject(sql, Integer.class, args);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public int[] insertUserSchemeMappings(String schemaName, List<UserSchemeMappingCreateRow> rows, int actorUserId) {
        validateSchemaName(schemaName);
        if (rows == null || rows.isEmpty()) {
            return new int[0];
        }

        String sql = String.format("""
                INSERT INTO %s.user_scheme_mapping_table
                    (user_id, scheme_id, status, created_by, created_at, updated_by, updated_at, deleted_at, deleted_by)
                VALUES (?, ?, 1, ?, NOW(), ?, NOW(), NULL, NULL)
                ON CONFLICT DO NOTHING
                """, schemaName);

        return jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                UserSchemeMappingCreateRow row = rows.get(i);
                ps.setLong(1, row.userId());
                ps.setInt(2, row.schemeId());
                ps.setInt(3, actorUserId);
                ps.setInt(4, actorUserId);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public int markUserSchemeMappingsDeleted(String schemaName, List<Long> userIds, int actorUserId) {
        validateSchemaName(schemaName);
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }

        String sql = String.format("""
                UPDATE %s.user_scheme_mapping_table
                SET deleted_at = NOW(), deleted_by = ?, updated_by = ?, updated_at = NOW()
                WHERE deleted_at IS NULL
                  AND user_id IN (%s)
                """, schemaName, placeholders);

        List<Object> args = new java.util.ArrayList<>(userIds.size() + 2);
        args.add(actorUserId);
        args.add(actorUserId);
        args.addAll(userIds);

        return jdbcTemplate.update(sql, args.toArray());
    }

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || schemaName.isBlank() || !SAFE_SCHEMA.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }
}
