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

        SchemeDTO body = controller.getSchemeDetails(11, "KA").getBody();

        assertThat(body).isEqualTo(dto);
        verify(schemeDbRepository).findSchemeById("tenant_ka", 11);
    }

    @Test
    void getSchemeDetails_throwsNotFoundWhenMissing() {
        when(schemeDbRepository.findSchemeById("tenant_ka", 99)).thenReturn(null);

        assertThatThrownBy(() -> controller.getSchemeDetails(99, "ka"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
