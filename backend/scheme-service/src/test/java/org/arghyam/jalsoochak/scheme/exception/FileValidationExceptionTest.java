package org.arghyam.jalsoochak.scheme.exception;

import org.arghyam.jalsoochak.scheme.dto.SchemeUploadErrorDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileValidationExceptionTest {

    @Test
    void exposesMessageAndErrors() {
        SchemeUploadErrorDTO error = SchemeUploadErrorDTO.builder().rowNumber(1).field("f").message("m").build();
        FileValidationException ex = new FileValidationException("bad file", List.of(error));

        assertThat(ex.getMessage()).isEqualTo("bad file");
        assertThat(ex.getErrors()).containsExactly(error);
    }
}
