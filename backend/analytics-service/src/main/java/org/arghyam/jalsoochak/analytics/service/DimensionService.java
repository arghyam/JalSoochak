package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.LgdLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterSupplyThresholdUpdatedEvent;

public interface DimensionService {

    void upsertTenant(TenantEvent event);

    void upsertUser(UserEvent event);

    void upsertScheme(SchemeEvent event);

    void upsertLgdLocation(LgdLocationEvent event);

    void upsertDepartmentLocation(DepartmentLocationEvent event);

    void updateWaterNorm(WaterNormUpdatedEvent event);

    void updateLocationHierarchyNames(TenantLocationHierarchyUpdatedEvent event);

    void updateWaterSupplyThreshold(WaterSupplyThresholdUpdatedEvent event);
}
