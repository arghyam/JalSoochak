package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemePerformanceEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.analytics.dto.event.AnomalyEvent;

public interface FactService {

    void ingestMeterReading(MeterReadingEvent event);

    void ingestWaterQuantity(WaterQuantityEvent event);

    void ingestEscalation(EscalationEvent event);

    void ingestSchemePerformance(SchemePerformanceEvent event);

    void ingestTenantEscalation(TenantEscalationEvent event);

    //a
    void ingestAnomalyRecorded(AnomalyEvent event);
}
