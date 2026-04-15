package org.arghyam.jalsoochak.scheme.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.assertj.core.api.Assertions.assertThatCode;

class WebConfigTest {

    @Test
    void addInterceptors_registersWithoutErrors() {
        WebConfig config = new WebConfig();
        InterceptorRegistry registry = new InterceptorRegistry();

        assertThatCode(() -> config.addInterceptors(registry)).doesNotThrowAnyException();
    }
}
