package org.arghyam.jalsoochak.scheme.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.repository.SchemeCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.repository.SchemeLgdMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeSubdivisionMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeUpdateRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runs inserts in small transactions so very large uploads don't create a single massive transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemeUploadChunkProcessor {

    private final SchemeDbRepository schemeDbRepository;

    @Transactional
    public int insertSchemesChunk(String schemaName, List<SchemeCreateRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        schemeDbRepository.insertSchemes(schemaName, rows);
        log.info("[scheme-upload] chunk_inserted type=schemes count={}", rows.size());
        return rows.size();
    }

    @Transactional
    public int updateSchemesChunk(String schemaName, List<SchemeUpdateRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        schemeDbRepository.updateSchemes(schemaName, rows);
        log.info("[scheme-upload] chunk_updated type=schemes count={}", rows.size());
        return rows.size();
    }

    @Transactional
    public int insertMappingsChunk(
            String schemaName,
            List<SchemeLgdMappingCreateRecord> lgdRows,
            List<SchemeSubdivisionMappingCreateRecord> departmentRows
    ) {
        int inserted = 0;
        if (lgdRows != null && !lgdRows.isEmpty()) {
            schemeDbRepository.insertLgdMappings(schemaName, lgdRows);
            inserted += lgdRows.size();
        }
        if (departmentRows != null && !departmentRows.isEmpty()) {
            schemeDbRepository.insertSubdivisionMappings(schemaName, departmentRows);
        }
        log.info("[scheme-upload] chunk_inserted type=mappings lgd={} dept={}",
                lgdRows == null ? 0 : lgdRows.size(),
                departmentRows == null ? 0 : departmentRows.size());
        return inserted;
    }
}
