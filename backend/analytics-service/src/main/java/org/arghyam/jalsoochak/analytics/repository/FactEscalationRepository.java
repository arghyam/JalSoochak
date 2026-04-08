package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactEscalationRepository extends JpaRepository<FactEscalation, Long>, JpaSpecificationExecutor<FactEscalation> {

    List<FactEscalation> findByTenantId(Integer tenantId);

    List<FactEscalation> findBySchemeId(Integer schemeId);

    List<FactEscalation> findByResolutionStatus(Integer resolutionStatus);

    List<FactEscalation> findByTenantIdAndResolutionStatus(Integer tenantId, Integer resolutionStatus);

    boolean existsByCorrelationId(String correlationId);

    Optional<FactEscalation> findByIdAndTenantIdAndUserId(Long id, Integer tenantId, Integer userId);

    Optional<FactEscalation> findFirstByTenantIdAndUserIdAndCorrelationIdOrderByCreatedAtDesc(
            Integer tenantId,
            Integer userId,
            String correlationId
    );
}
