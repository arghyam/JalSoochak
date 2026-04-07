package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsStatusController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsStatusControllerTest {

    private static final String BASE = "/api/v1/analytics";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getAnomalyStatuses_returnsCodesAndLabels() throws Exception {
        mockMvc.perform(get(BASE + "/anomalies/statuses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value(0))
                .andExpect(jsonPath("$.data[0].label").value("Unresolved"))
                .andExpect(jsonPath("$.data[1].code").value(1))
                .andExpect(jsonPath("$.data[1].label").value("In-Progress"))
                .andExpect(jsonPath("$.data[2].code").value(2))
                .andExpect(jsonPath("$.data[2].label").value("Resolved"));
    }

    @Test
    void getEscalationStatuses_returnsCodesAndLabels() throws Exception {
        mockMvc.perform(get(BASE + "/escalations/statuses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value(0))
                .andExpect(jsonPath("$.data[0].label").value("Unresolved"))
                .andExpect(jsonPath("$.data[1].code").value(1))
                .andExpect(jsonPath("$.data[1].label").value("In-Progress"))
                .andExpect(jsonPath("$.data[2].code").value(2))
                .andExpect(jsonPath("$.data[2].label").value("Resolved"));
    }
}
