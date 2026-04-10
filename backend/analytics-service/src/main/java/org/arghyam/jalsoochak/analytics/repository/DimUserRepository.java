package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DimUserRepository extends JpaRepository<DimUser, Long> {

    List<DimUser> findByTenantId(Integer tenantId);

    Optional<DimUser> findByUuid(UUID uuid);

    Optional<DimUser> findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(Integer tenantId, UUID uuid);

    Optional<DimUser> findByTenantIdAndUserId(Integer tenantId, Integer userId);
}
