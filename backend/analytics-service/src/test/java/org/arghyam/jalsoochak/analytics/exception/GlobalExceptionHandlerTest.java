package org.arghyam.jalsoochak.analytics.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void handleValidationErrors_returnsBadRequestWithMessages() throws Exception {
        mockMvc.perform(post("/probe/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages").isArray());
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("bad"));
    }

    @Test
    void handleMissingParam_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter: q"));
    }

    @Test
    void handleIllegalState_returnsConflict() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("in use"));
    }

    @Test
    void handleGeneral_returnsInternalServerError() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Controller
    @RequestMapping("/probe")
    static class ProbeController {

        record Body(@NotBlank String name) {
        }

        @PostMapping("/valid")
        @ResponseBody
        void valid(@Valid @RequestBody Body body) {
        }

        @GetMapping("/illegal")
        @ResponseBody
        void illegal() {
            throw new IllegalArgumentException("bad");
        }

        @GetMapping("/param")
        @ResponseBody
        void param(@RequestParam("q") String q) {
        }

        @GetMapping("/conflict")
        @ResponseBody
        void conflict() {
            throw new IllegalStateException("in use");
        }

        @GetMapping("/boom")
        @ResponseBody
        void boom() {
            throw new RuntimeException("boom");
        }
    }
}
