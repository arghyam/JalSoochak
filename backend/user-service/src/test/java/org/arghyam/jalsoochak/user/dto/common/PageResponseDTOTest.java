package org.arghyam.jalsoochak.user.dto.common;

import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PageResponseDTO")
class PageResponseDTOTest {

    @Nested
    @DisplayName("of()")
    class Of {

        @Test
        @DisplayName("builds correct page for first page with results")
        void firstPage() {
            List<String> items = List.of("a", "b", "c");
            PageResponseDTO<String> page = PageResponseDTO.of(items, 25L, 0, 10);

            assertThat(page.getContent()).isEqualTo(items);
            assertThat(page.getTotalElements()).isEqualTo(25L);
            assertThat(page.getTotalPages()).isEqualTo(3);
            assertThat(page.getNumber()).isEqualTo(0);
            assertThat(page.getSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("builds correct page for middle page")
        void middlePage() {
            PageResponseDTO<Integer> page = PageResponseDTO.of(List.of(1), 30L, 2, 10);

            assertThat(page.getTotalPages()).isEqualTo(3);
            assertThat(page.getNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("totalPages rounds up when total is not a multiple of size")
        void roundsUpTotalPages() {
            PageResponseDTO<String> page = PageResponseDTO.of(List.of("x"), 11L, 0, 10);

            assertThat(page.getTotalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("page 0 with 0 totalElements returns totalPages=0")
        void emptyResultSet() {
            PageResponseDTO<String> page = PageResponseDTO.of(List.of(), 0L, 0, 10);

            assertThat(page.getTotalPages()).isEqualTo(0);
            assertThat(page.getTotalElements()).isEqualTo(0L);
            assertThat(page.getContent()).isEmpty();
        }

        @Test
        @DisplayName("single page when totalElements equals size exactly")
        void exactOnePage() {
            PageResponseDTO<String> page = PageResponseDTO.of(List.of("a", "b"), 2L, 0, 2);

            assertThat(page.getTotalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("throws BadRequestException when size is 0")
        void sizeZero() {
            assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 0L, 0, 0))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("size");
        }

        @Test
        @DisplayName("throws BadRequestException when size is negative")
        void sizeNegative() {
            assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 0L, 0, -1))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("throws BadRequestException when page is negative")
        void pageNegative() {
            assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 10L, -1, 10))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("page");
        }

        @Test
        @DisplayName("throws BadRequestException when totalElements is negative")
        void totalElementsNegative() {
            assertThatThrownBy(() -> PageResponseDTO.of(List.of(), -1L, 0, 10))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("totalElements");
        }

        @Test
        @DisplayName("throws BadRequestException when page is beyond totalPages")
        void pageOutOfRange() {
            // 15 total, size 10 → 2 pages (0,1). Requesting page 2 is OOB.
            assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 15L, 2, 10))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("out of range");
        }

        @Test
        @DisplayName("throws BadRequestException when page > 0 but totalElements is 0")
        void nonZeroPageOnEmptyResult() {
            assertThatThrownBy(() -> PageResponseDTO.of(List.of(), 0L, 1, 10))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("out of range");
        }

        @Test
        @DisplayName("does not throw when page equals totalPages - 1 (last page)")
        void lastPage() {
            PageResponseDTO<String> page = PageResponseDTO.of(List.of("x"), 25L, 2, 10);

            assertThat(page.getNumber()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(3);
        }
    }
}
