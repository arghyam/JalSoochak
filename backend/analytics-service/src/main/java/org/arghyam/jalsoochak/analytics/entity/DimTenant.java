package org.arghyam.jalsoochak.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "dim_tenant_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimTenant {

    @Id
    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(nullable = false)
    private String title;

    @Column(name = "country_code")
    private String countryCode;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "required_lpcd")
    private Integer requiredLpcd;

    @Column(name = "over_supply_range_percentage")
    private Integer overSupplyRangePercentage;

    @Column(name = "under_supply_range_percentage")
    private Integer underSupplyRangePercentage;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "included_work_statuses", columnDefinition = "integer[]")
    private List<Integer> includedWorkStatuses;

    /**
     * Percentage of days on which a scheme must supply water to count as regular. NULL means "not
     * configured" — {@code RegularityThresholdFilter} then falls back to the national default
     * (tenant-0) and finally the analytics env default.
     */
    @Column(name = "regularity_threshold_percent")
    private BigDecimal regularityThresholdPercent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
