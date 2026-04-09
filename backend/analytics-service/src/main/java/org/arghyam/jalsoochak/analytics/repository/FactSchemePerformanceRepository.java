package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FactSchemePerformanceRepository extends JpaRepository<FactSchemePerformance, Long> {

    List<FactSchemePerformance> findByTenantId(Integer tenantId);

    List<FactSchemePerformance> findByTenantIdAndSchemeId(Integer tenantId, Integer schemeId);

    List<FactSchemePerformance> findBySchemeId(Integer schemeId);
}
