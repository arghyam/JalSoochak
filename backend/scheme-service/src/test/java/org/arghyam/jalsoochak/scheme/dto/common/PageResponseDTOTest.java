package org.arghyam.jalsoochak.scheme.dto.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseDTOTest {

    @Test
    void of_calculatesTotalPages() {
        PageResponseDTO<String> page = PageResponseDTO.of(List.of("a", "b"), 21, 1, 10);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    void of_handlesZeroSize() {
        PageResponseDTO<String> page = PageResponseDTO.of(List.of(), 21, 0, 0);
        assertThat(page.getTotalPages()).isZero();
    }
}
