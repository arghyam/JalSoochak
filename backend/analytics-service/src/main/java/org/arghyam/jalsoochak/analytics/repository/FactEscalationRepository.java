package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FactEscalationRepository extends JpaRepository<FactEscalation, Long>, JpaSpecificationExecutor<FactEscalation> {

    List<FactEscalation> findByTenantId(Integer tenantId);

    List<FactEscalation> findBySchemeId(Integer schemeId);

    List<FactEscalation> findByResolutionStatus(Integer resolutionStatus);

    List<FactEscalation> findByTenantIdAndResolutionStatus(Integer tenantId, Integer resolutionStatus);

    boolean existsByCorrelationId(String correlationId);
}
