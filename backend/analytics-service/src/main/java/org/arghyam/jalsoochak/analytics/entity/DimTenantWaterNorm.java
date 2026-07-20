package org.arghyam.jalsoochak.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * SCD Type-2 effective-dated history of a tenant's water-norm values.
 *
 * <p>Intervals are half-open: a row applies for dates {@code d} where
 * {@code effective_from <= d AND (effective_to IS NULL OR d < effective_to)}.
 * The single row with {@code effectiveTo == null} is the currently-in-effect one.</p>
 */
@Entity
@Table(name = "dim_tenant_water_norm_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimTenantWaterNorm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Integer tenantId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** NULL marks the currently-in-effect row. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "required_lpcd")
    private Integer requiredLpcd;

    @Column(name = "person_count_per_household")
    private Integer personCountPerHousehold;

    @Column(name = "over_supply_range_percentage")
    private Integer overSupplyRangePercentage;

    @Column(name = "under_supply_range_percentage")
    private Integer underSupplyRangePercentage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
