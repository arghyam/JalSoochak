package org.arghyam.jalsoochak.scheme.exception;

import org.arghyam.jalsoochak.scheme.controller.PublicSchemeController;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the advice is actually consulted for the exceptions Spring MVC raises while binding a
 * request, rather than only that its methods return the right status when called directly.
 *
 * <p>The distinction matters: {@code @ExceptionHandler(Exception.class)} is the closest match for
 * anything not mapped explicitly, so before those mappings existed each of these produced a 500.
 * A unit test that invokes a handler by hand cannot catch that regression coming back — only a
 * dispatch through {@code ExceptionHandlerExceptionResolver} can.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerDispatchTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new PublicSchemeController(schemeDbRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void nonNumericPathVariableIsBadRequestNotServerError() throws Exception {
        mockMvc().perform(get("/api/v1/public/schemes/abc").param("tenantCode", "KA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'abc' for parameter 'schemeId'"));
    }

    @Test
    void missingRequiredParamIsBadRequestNotServerError() throws Exception {
        mockMvc().perform(get("/api/v1/public/schemes/11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'tenantCode' is missing"));
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowedNotServerError() throws Exception {
        mockMvc().perform(post("/api/v1/public/schemes/11").param("tenantCode", "KA"))
                .andExpect(status().isMethodNotAllowed());
    }
}
