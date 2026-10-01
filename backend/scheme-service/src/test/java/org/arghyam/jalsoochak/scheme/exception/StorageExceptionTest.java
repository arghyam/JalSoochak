package org.arghyam.jalsoochak.scheme.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageExceptionTest {

    @Test
    void messageConstructor() {
        assertThat(new StorageException("upload failed"))
                .hasMessage("upload failed")
                .hasNoCause();
    }

    @Test
    void messageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("io");
        assertThat(new StorageException("upload failed", cause))
                .hasMessage("upload failed")
                .hasCause(cause);
    }
}
