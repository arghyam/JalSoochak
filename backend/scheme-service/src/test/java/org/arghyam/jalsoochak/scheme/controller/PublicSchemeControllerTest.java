package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicSchemeControllerTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @InjectMocks
    PublicSchemeController controller;

    @Test
    void getSchemeDetails_returnsSchemeWhenFound() {
        SchemeDTO dto = SchemeDTO.builder().id(11).schemeName("Scheme A").build();
        when(schemeDbRepository.findSchemeById("tenant_ka", 11)).thenReturn(dto);

        SchemeDTO body = controller.getSchemeDetails(11, "KA", null).getBody();

        assertThat(body).isEqualTo(dto);
        verify(schemeDbRepository).findSchemeById("tenant_ka", 11);
    }

    @Test
    void getSchemeDetails_throwsNotFoundWhenMissing() {
        when(schemeDbRepository.findSchemeById("tenant_ka", 99)).thenReturn(null);

        assertThatThrownBy(() -> controller.getSchemeDetails(99, "ka", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getSchemeDetails_skipsTenantIdLookupWhenParameterOmitted() {
        SchemeDTO dto = SchemeDTO.builder().id(11).schemeName("Scheme A").build();
        when(schemeDbRepository.findSchemeById("tenant_ka", 11)).thenReturn(dto);

        controller.getSchemeDetails(11, "KA", null);

        verify(schemeDbRepository, never()).findSchemaNameByTenantId(anyInt());
    }

    @Test
    void getSchemeDetails_returnsSchemeWhenTenantIdMatchesTenantCode() {
        SchemeDTO dto = SchemeDTO.builder().id(11).schemeName("Scheme A").build();
        when(schemeDbRepository.findSchemaNameByTenantId(17)).thenReturn("tenant_ka");
        when(schemeDbRepository.findSchemeById("tenant_ka", 11)).thenReturn(dto);

        SchemeDTO body = controller.getSchemeDetails(11, "KA", 17).getBody();

        assertThat(body).isEqualTo(dto);
    }

    @Test
    void getSchemeDetails_throwsBadRequestWhenTenantIdNamesAnotherTenant() {
        when(schemeDbRepository.findSchemaNameByTenantId(1)).thenReturn("tenant_mp");

        assertThatThrownBy(() -> controller.getSchemeDetails(11, "KA", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(schemeDbRepository, never()).findSchemeById(anyString(), anyInt());
    }

    @Test
    void getSchemeDetails_throwsBadRequestWhenTenantIdIsUnknown() {
        when(schemeDbRepository.findSchemaNameByTenantId(99)).thenReturn(null);

        assertThatThrownBy(() -> controller.getSchemeDetails(11, "KA", 99))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(schemeDbRepository, never()).findSchemeById(anyString(), anyInt());
    }

    @Test
    void getSchemeDetails_throwsBadRequestWhenTenantIdIsZero() {
        when(schemeDbRepository.findSchemaNameByTenantId(0)).thenReturn(null);

        assertThatThrownBy(() -> controller.getSchemeDetails(11, "KA", 0))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getSchemeDetails_rejectsInvalidTenantCodeBeforeAnyLookup() {
        assertThatThrownBy(() -> controller.getSchemeDetails(11, "ka; DROP TABLE", 17))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(schemeDbRepository, never()).findSchemaNameByTenantId(anyInt());
        verify(schemeDbRepository, never()).findSchemeById(anyString(), anyInt());
    }
}
