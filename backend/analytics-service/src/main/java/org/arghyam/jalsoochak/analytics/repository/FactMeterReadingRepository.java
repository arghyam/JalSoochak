package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FactMeterReadingRepository extends JpaRepository<FactMeterReading, Long> {

    List<FactMeterReading> findByTenantIdAndSchemeIdAndReadingDateBetween(
            Integer tenantId,
            Integer schemeId,
            LocalDate startDate,
            LocalDate endDate
    );

    Optional<FactMeterReading> findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDesc(
            Integer tenantId,
            Integer schemeId,
            LocalDate readingDate
    );
}
