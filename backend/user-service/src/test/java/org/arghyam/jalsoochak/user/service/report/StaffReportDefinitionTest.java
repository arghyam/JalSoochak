package org.arghyam.jalsoochak.user.service.report;

import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeSummaryDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.TenantStaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaffReportDefinition")
class StaffReportDefinitionTest {

    @Mock TenantStaffRepository tenantStaffRepository;

    private StaffReportDefinition definition;

    @BeforeEach
    void setUp() {
        definition = new StaffReportDefinition(tenantStaffRepository);
    }

    @Test
    @DisplayName("type() and resourceType() are stable identifiers")
    void identifiers() {
        assertThat(definition.type()).isEqualTo("TENANT_STAFF");
        assertThat(definition.resourceType()).isEqualTo(ResourceType.STAFF_USERS);
        assertThat(definition.downloadFilenamePrefix()).isEqualTo("staff_report");
    }

    @Test
    @DisplayName("schema exposes 8 columns in stable header order")
    void schemaColumns() {
        List<String> headers = definition.schema().headers();
        assertThat(headers).containsExactly(
                "ID", "UUID", "Name", "Email", "Phone Number",
                "Status", "Role", "Schemes");
    }

    @Test
    @DisplayName("schema extractors render scalar fields and join schemes with '; '")
    void schemaExtractors() {
        TenantStaffResponseDTO row = TenantStaffResponseDTO.builder()
                .id(42L).uuid("u-1").title("Anita")
                .email("a@x").phoneNumber("919999999999")
                .status(TenantUserStatus.ACTIVE).role("DISTRICT_OFFICER")
                .schemes(List.of(
                        new SchemeSummaryDTO(1L, "Scheme A", null, null),
                        new SchemeSummaryDTO(2L, "Scheme B", null, null)))
                .build();

        ReportSchema<TenantStaffResponseDTO> schema = definition.schema();
        List<ReportColumn<TenantStaffResponseDTO>> columns = schema.columns();
        assertThat(columns.get(0).extractor().apply(row)).isEqualTo("42");
        assertThat(columns.get(1).extractor().apply(row)).isEqualTo("u-1");
        assertThat(columns.get(2).extractor().apply(row)).isEqualTo("Anita");
        assertThat(columns.get(5).extractor().apply(row)).isEqualTo("ACTIVE");
        assertThat(columns.get(7).extractor().apply(row)).isEqualTo("Scheme A; Scheme B");
    }

    @Test
    @DisplayName("schema extractors render null fields and null status as empty string")
    void schemaExtractorsNullSafe() {
        TenantStaffResponseDTO row = TenantStaffResponseDTO.builder().build();
        ReportSchema<TenantStaffResponseDTO> schema = definition.schema();
        for (ReportColumn<TenantStaffResponseDTO> col : schema.columns()) {
            Object v = col.extractor().apply(row);
            assertThat(v == null ? "" : v.toString()).doesNotContain("null");
        }
    }

    @Test
    @DisplayName("schemes column ignores null entries and empty names")
    void schemesColumnFiltersBlanks() {
        TenantStaffResponseDTO row = TenantStaffResponseDTO.builder()
                .schemes(List.of(
                        new SchemeSummaryDTO(1L, "Real", null, null),
                        new SchemeSummaryDTO(2L, "", null, null),
                        new SchemeSummaryDTO(3L, null, null, null)))
                .build();
        Object cell = definition.schema().columns().get(7).extractor().apply(row);
        assertThat(cell).isEqualTo("Real");
    }

    @Test
    @DisplayName("schemes column skips null SchemeSummaryDTO elements without NPE")
    void schemesColumnSkipsNullElement() {
        java.util.List<SchemeSummaryDTO> schemes = new java.util.ArrayList<>();
        schemes.add(new SchemeSummaryDTO(1L, "Valid", null, null));
        schemes.add(null);
        schemes.add(new SchemeSummaryDTO(3L, "Also Valid", null, null));
        TenantStaffResponseDTO row = TenantStaffResponseDTO.builder().schemes(schemes).build();
        Object cell = definition.schema().columns().get(7).extractor().apply(row);
        assertThat(cell).isEqualTo("Valid; Also Valid");
    }

    @Test
    @DisplayName("parseStatus rejects numeric value exceeding Integer.MAX_VALUE with BadRequestException")
    void parseStatusRejectsOverflowNumeric() {
        String overflow = String.valueOf((long) Integer.MAX_VALUE + 1); // "2147483648"
        assertThatThrownBy(() -> definition.fetch("tenant_mp",
                new StaffReportRequestDTO(null, overflow, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid status value");
    }

    @Test
    @DisplayName("normalize lower-cases / dedupes / sorts roles and trims status & name")
    void normalizeRoles() {
        StaffReportRequestDTO in = new StaffReportRequestDTO(
                List.of("DISTRICT_OFFICER", "section_officer", " District_Officer "),
                "  active  ",
                "  Anita  ");
        StaffReportRequestDTO out = definition.normalize(in);

        assertThat(out.roles()).containsExactly("district_officer", "section_officer");
        assertThat(out.status()).isEqualTo("ACTIVE");
        assertThat(out.name()).isEqualTo("Anita");
    }

    @Test
    @DisplayName("normalize splits comma-separated role strings")
    void normalizeSplitsCsvRoles() {
        StaffReportRequestDTO out = definition.normalize(
                new StaffReportRequestDTO(List.of("DISTRICT_OFFICER,SECTION_OFFICER"), null, null));
        assertThat(out.roles()).containsExactly("district_officer", "section_officer");
    }

    @Test
    @DisplayName("normalize tolerates null input and produces empty filters")
    void normalizeNullInput() {
        StaffReportRequestDTO out = definition.normalize(null);
        assertThat(out.roles()).isEmpty();
        assertThat(out.status()).isNull();
        assertThat(out.name()).isNull();
    }

    @Test
    @DisplayName("fetch delegates to repository with normalized filters and ACTIVE status code")
    void fetchDelegatesActiveStatus() {
        when(tenantStaffRepository.listAllStaffForExport(
                eq("tenant_mp"), any(), any(), any()))
                .thenReturn(List.of());
        definition.fetch("tenant_mp", new StaffReportRequestDTO(
                List.of("district_officer"), "ACTIVE", null));

        ArgumentCaptor<Integer> statusCap = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(tenantStaffRepository).listAllStaffForExport(
                eq("tenant_mp"), eq(List.of("district_officer")), statusCap.capture(), any());
        assertThat(statusCap.getValue()).isEqualTo(TenantUserStatus.ACTIVE.code);
    }

    @Test
    @DisplayName("fetch passes through numeric status codes verbatim")
    void fetchPassesNumericStatus() {
        when(tenantStaffRepository.listAllStaffForExport(any(), any(), any(), any()))
                .thenReturn(List.of());
        definition.fetch("tenant_mp", new StaffReportRequestDTO(null, "1", null));

        ArgumentCaptor<Integer> statusCap = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(tenantStaffRepository).listAllStaffForExport(
                eq("tenant_mp"), any(), statusCap.capture(), any());
        assertThat(statusCap.getValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("fetch rejects unknown status with BadRequestException")
    void fetchRejectsUnknownStatus() {
        assertThatThrownBy(() -> definition.fetch("tenant_mp",
                new StaffReportRequestDTO(null, "PENDING", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown status: PENDING");
    }
}
