package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DimLgdLocationRepository extends JpaRepository<DimLgdLocation, Integer> {

    List<DimLgdLocation> findByTenantId(Integer tenantId);

    List<DimLgdLocation> findByLgdLevel(Integer lgdLevel);

    Optional<DimLgdLocation> findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(Integer tenantId, Integer lgdLevel);
}
