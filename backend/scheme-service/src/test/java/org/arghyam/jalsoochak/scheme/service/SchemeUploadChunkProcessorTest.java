package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.repository.SchemeCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.repository.SchemeLgdMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeSubdivisionMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeUpdateRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SchemeUploadChunkProcessorTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @InjectMocks
    SchemeUploadChunkProcessor processor;

    @Test
    void insertSchemesChunkReturnsZeroForNullOrEmpty() {
        assertThat(processor.insertSchemesChunk("tenant_ka", null)).isZero();
        assertThat(processor.insertSchemesChunk("tenant_ka", List.of())).isZero();
        verifyNoInteractions(schemeDbRepository);
    }

    @Test
    void insertSchemesChunkInsertsAndReturnsRowCount() {
        List<SchemeCreateRecord> rows = List.of(
                new SchemeCreateRecord("u1", "S1", "C1", "Scheme 1", 1, 1, 2, 12.1, 77.1, null, 1, 1, 10, 10)
        );

        int inserted = processor.insertSchemesChunk("tenant_ka", rows);

        assertThat(inserted).isEqualTo(1);
        verify(schemeDbRepository).insertSchemes("tenant_ka", rows);
    }

    @Test
    void updateSchemesChunkReturnsZeroForNullOrEmpty() {
        assertThat(processor.updateSchemesChunk("tenant_ka", null)).isZero();
        assertThat(processor.updateSchemesChunk("tenant_ka", List.of())).isZero();
        verifyNoInteractions(schemeDbRepository);
    }

    @Test
    void updateSchemesChunkUpdatesAndReturnsRowCount() {
        List<SchemeUpdateRecord> rows = List.of(
                new SchemeUpdateRecord(1, "S1", "C1", "Scheme 1", 1, 1, 2, 12.1, 77.1, 1, 1, 10)
        );

        int updated = processor.updateSchemesChunk("tenant_ka", rows);

        assertThat(updated).isEqualTo(1);
        verify(schemeDbRepository).updateSchemes("tenant_ka", rows);
    }

    @Test
    void insertMappingsChunkHandlesAllInputCombinations() {
        List<SchemeLgdMappingCreateRecord> lgdRows = List.of(
                new SchemeLgdMappingCreateRecord(1, 101, 6, 10, 10)
        );
        List<SchemeSubdivisionMappingCreateRecord> deptRows = List.of(
                new SchemeSubdivisionMappingCreateRecord(1, 201, "sub_division", 10, 10)
        );

        int inserted = processor.insertMappingsChunk("tenant_ka", lgdRows, deptRows);
        assertThat(inserted).isEqualTo(1);
        verify(schemeDbRepository).insertLgdMappings("tenant_ka", lgdRows);
        verify(schemeDbRepository).insertSubdivisionMappings("tenant_ka", deptRows);

        int insertedOnlyDept = processor.insertMappingsChunk("tenant_ka", List.of(), deptRows);
        assertThat(insertedOnlyDept).isZero();

        int insertedNone = processor.insertMappingsChunk("tenant_ka", null, null);
        assertThat(insertedNone).isZero();
    }
}
