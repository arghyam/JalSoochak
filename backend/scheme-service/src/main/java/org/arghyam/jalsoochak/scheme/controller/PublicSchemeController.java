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

    // Despite the /public prefix this endpoint is authenticated — scheme-service's SecurityConfig
    // has no permitAll for /api/v1/public/**, and the gateway only opens /user/api/v1/public/**.
    // It is therefore guarded like every other tenant-scoped read. If it is ever made genuinely
    // public, drop this annotation along with adding the permitAll entries.
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

