package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DimTenantRepository extends JpaRepository<DimTenant, Integer> {

    List<DimTenant> findByStatus(Integer status);

    Optional<DimTenant> findByStateCode(String stateCode);

    List<DimTenant> findByTenantIdGreaterThan(Integer tenantId);
}
