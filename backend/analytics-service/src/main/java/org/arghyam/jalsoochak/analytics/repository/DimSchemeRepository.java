package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DimSchemeRepository extends JpaRepository<DimScheme, Integer> {

    List<DimScheme> findByTenantId(Integer tenantId);

    Optional<DimScheme> findTopByTenantIdAndSchemeIdOrderByUpdatedAtDescCreatedAtDesc(Integer tenantId, Integer schemeId);
}
