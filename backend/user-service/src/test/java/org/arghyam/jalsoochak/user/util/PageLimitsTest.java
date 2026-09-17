package org.arghyam.jalsoochak.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PageLimits")
class PageLimitsTest {

    @Test
    @DisplayName("caps size=99999 at the server-side maximum instead of exporting the table")
    void capsOversizedPage() {
        assertThat(PageLimits.clampSize(99999)).isEqualTo(PageLimits.MAX_PAGE_SIZE);
        assertThat(PageLimits.clampSize(Integer.MAX_VALUE)).isEqualTo(PageLimits.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("raises a non-positive size to 1 rather than producing LIMIT 0 or a negative LIMIT")
    void raisesNonPositiveSize() {
        assertThat(PageLimits.clampSize(0)).isEqualTo(1);
        assertThat(PageLimits.clampSize(-5)).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps a size within range untouched")
    void keepsValidSize() {
        assertThat(PageLimits.clampSize(20)).isEqualTo(20);
        assertThat(PageLimits.clampSize(PageLimits.MAX_PAGE_SIZE)).isEqualTo(PageLimits.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("floors a negative page at 0")
    void floorsNegativePage() {
        assertThat(PageLimits.clampPage(-1)).isZero();
        assertThat(PageLimits.clampPage(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("saturates the offset instead of overflowing int into a negative OFFSET")
    void saturatesOffset() {
        assertThat(PageLimits.offset(Integer.MAX_VALUE, PageLimits.MAX_PAGE_SIZE))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(PageLimits.offset(2, 20)).isEqualTo(40);
        assertThat(PageLimits.offset(-1, 20)).isZero();
    }
}
