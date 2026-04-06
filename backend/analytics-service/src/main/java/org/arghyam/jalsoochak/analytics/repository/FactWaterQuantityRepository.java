package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FactWaterQuantityRepository extends JpaRepository<FactWaterQuantity, Long> {

    List<FactWaterQuantity> findByTenantId(Integer tenantId);

    List<FactWaterQuantity> findBySchemeId(Integer schemeId);

    List<FactWaterQuantity> findByTenantIdAndDateBetween(Integer tenantId, LocalDate startDate, LocalDate endDate);

    List<FactWaterQuantity> findBySchemeIdAndDateBetween(Integer schemeId, LocalDate startDate, LocalDate endDate);

    Optional<FactWaterQuantity> findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
            Integer tenantId,
            Integer schemeId,
            LocalDate date
    );
}
