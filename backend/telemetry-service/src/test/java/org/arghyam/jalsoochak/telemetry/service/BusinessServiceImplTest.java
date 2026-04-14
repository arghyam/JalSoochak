package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.SampleDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessServiceImplTest {

    @Test
    void getAllReadingsReturnsExpectedSeedData() {
        BusinessServiceImpl service = new BusinessServiceImpl();

        List<SampleDTO> rows = service.getAllReadings();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getMeterId()).isEqualTo("METER-001");
        assertThat(rows.get(0).getReadingValue()).isEqualTo(150.5);
        assertThat(rows.get(1).getMeterId()).isEqualTo("METER-002");
        assertThat(rows.get(1).getReadingValue()).isEqualTo(230.8);
    }
}
