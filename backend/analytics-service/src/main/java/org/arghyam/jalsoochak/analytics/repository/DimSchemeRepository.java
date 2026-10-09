package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DimSchemeRepository extends JpaRepository<DimScheme, Integer> {

    List<DimScheme> findByTenantId(Integer tenantId);
}
