package org.arghyam.jalsoochak.scheme.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnsupportedFileTypeExceptionTest {

    @Test
    void storesMessage() {
        UnsupportedFileTypeException ex = new UnsupportedFileTypeException("unsupported");
        assertThat(ex.getMessage()).isEqualTo("unsupported");
    }
}
