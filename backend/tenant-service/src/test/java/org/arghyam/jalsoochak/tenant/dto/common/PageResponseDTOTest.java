package org.arghyam.jalsoochak.tenant.dto.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseDTOTest {

    @Test
    void of_returnsCorrectPage_forValidInput() {
        List<String> content = List.of("a", "b");
        PageResponseDTO<String> page = PageResponseDTO.of(content, 20L, 0, 10);

        assertThat(page.getContent()).isEqualTo(content);
        assertThat(page.getTotalElements()).isEqualTo(20L);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getNumber()).isEqualTo(0);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    void of_throwsIllegalArgumentException_whenSizeIsZero() {
        assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 10L, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be greater than 0");
    }

    @Test
    void of_throwsIllegalArgumentException_whenSizeIsNegative() {
        assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 10L, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be greater than 0");
    }

    @Test
    void of_throwsIllegalArgumentException_whenPageIsNegative() {
        assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 10L, -1, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must be non-negative");
    }

    @Test
    void of_throwsIllegalArgumentException_whenTotalElementsIsNegative() {
        assertThatThrownBy(() -> PageResponseDTO.of(List.of(), -1L, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalElements must be non-negative");
    }

    @Test
    void of_throwsIllegalArgumentException_whenPageOutOfRange_withNonZeroPage_andZeroTotalPages() {
        assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 0L, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void of_throwsIllegalArgumentException_whenPageEqualsTotalPages() {
        assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 10L, 2, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void of_allowsPageZero_whenTotalElementsIsZero() {
        PageResponseDTO<String> page = PageResponseDTO.of(List.of(), 0L, 0, 5);

        assertThat(page.getTotalPages()).isEqualTo(0);
        assertThat(page.getNumber()).isEqualTo(0);
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void of_calculatesCorrectTotalPages_withExactDivision() {
        PageResponseDTO<String> page = PageResponseDTO.of(List.of(), 10L, 0, 5);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void of_calculatesCorrectTotalPages_withRemainder() {
        PageResponseDTO<String> page = PageResponseDTO.of(List.of(), 11L, 0, 5);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }
}
