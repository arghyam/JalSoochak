package org.arghyam.jalsoochak.scheme.controller;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.scheme.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.util.TenantSchemaResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicSchemeController {

    private final SchemeDbRepository schemeDbRepository;

    /**
     * Returns one scheme from the tenant named by {@code tenantCode}.
     *
     * <p>Despite the /public prefix this endpoint is authenticated — scheme-service's
     * {@code SecurityConfig} has no permitAll for {@code /api/v1/public/**}, and the gateway only
     * opens {@code /user/api/v1/public/**}. It is therefore guarded like every other tenant-scoped
     * read. If it is ever made genuinely public, drop {@link RequiresTenantAccess} along with
     * adding the permitAll entries.
     *
     * <p>{@code tenantCode} is the sole tenant selector — it picks the schema and is matched
     * against the caller's own JWT by {@link RequiresTenantAccess}. {@code tenantId} is optional
     * and carried only for callers that already hold the numeric id; when supplied it must name
     * the same tenant, otherwise the request is rejected as malformed (400) rather than silently
     * ignored. Omitting it is valid and changes nothing.
     */
    @RequiresTenantAccess
    @GetMapping("/schemes/{schemeId}")
    public ResponseEntity<SchemeDTO> getSchemeDetails(
            @PathVariable int schemeId,
            @RequestParam String tenantCode,
            @RequestParam(required = false) Integer tenantId
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        requireTenantIdMatches(tenantId, schemaName);
        SchemeDTO dto = schemeDbRepository.findSchemeById(schemaName, schemeId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found");
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * Rejects a {@code tenantId} that names a different tenant than the already-resolved
     * {@code schemaName}, so the parameter cannot look enforced while being ignored.
     *
     * <p>A {@code null} id means "not supplied" and passes. An unknown id resolves to no schema
     * and is therefore a mismatch. This is an input-consistency check, not an access check — the
     * caller's entitlement to {@code schemaName} has already been settled by
     * {@link RequiresTenantAccess}, so a contradiction here is a 400, not a 403.
     */
    private void requireTenantIdMatches(Integer tenantId, String schemaName) {
        if (tenantId == null) {
            return;
        }
        if (!schemaName.equals(schemeDbRepository.findSchemaNameByTenantId(tenantId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId does not match tenantCode");
        }
    }
}

