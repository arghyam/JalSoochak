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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SCD Type-2 effective-dated history of the dashboard work_status filter
 * ({@code included_work_statuses}) per tier: {@code tenantId > 0} is a tenant's own
 * filter, {@code tenantId == 0} is the national default. Pre-aggregated KPIs are
 * built with the filter row in force for the period being aggregated, so stored
 * history stays reproducible when the filter changes later.
 *
 * <p>Intervals are half-open: a row applies for dates {@code d} where
 * {@code effective_from <= d AND (effective_to IS NULL OR d < effective_to)}.
 * The single row with {@code effectiveTo == null} is the currently-in-effect one.</p>
 */
@Entity
@Table(name = "dim_tenant_work_status_filter_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimTenantWorkStatusFilter {

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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "included_work_statuses", columnDefinition = "integer[]")
    private List<Integer> includedWorkStatuses;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
