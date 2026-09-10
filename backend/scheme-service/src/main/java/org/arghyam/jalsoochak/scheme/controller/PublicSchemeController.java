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
     * <p>{@code tenantCode} is the only tenant selector: it picks the schema and is matched against
     * the caller's own JWT by {@link RequiresTenantAccess}. A numeric {@code tenantId} is
     * deliberately not accepted — it would be a second selector carrying no authority of its own,
     * which invites callers to believe a request was scoped when only {@code tenantCode} scoped it.
     * Endpoints that address a tenant by id instead (see
     * {@code SchemeController#getSchemeStatuses}) authorize it through
     * {@code SchemeSecurityEvaluator#canAccessTenantId}.
     */
    @RequiresTenantAccess
    @GetMapping("/schemes/{schemeId}")
    public ResponseEntity<SchemeDTO> getSchemeDetails(
            @PathVariable int schemeId,
            @RequestParam String tenantCode
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        SchemeDTO dto = schemeDbRepository.findSchemeById(schemaName, schemeId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found");
        }
        return ResponseEntity.ok(dto);
    }
}
