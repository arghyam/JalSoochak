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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fact_water_quantity_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactWaterQuantity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Integer tenantId;

    @Column(name = "scheme_id", nullable = false)
    private Integer schemeId;

    @Column(name = "user_id")
    private Integer userId;

    /**
     * The day's supplied volume in <strong>litres</strong> (BIGINT since V45) — the unit every consumer
     * of this column already assumes. Writers convert from the meter's native m&sup3; through
     * {@link org.arghyam.jalsoochak.analytics.service.water.WaterVolumeUnits}.
     */
    @Column(name = "water_quantity", nullable = false)
    private Long waterQuantity;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "submission_status")
    private Integer submissionStatus;

    @Column(name = "outage_reason")
    private String outageReason;

    @Column(name = "non_submission_reason")
    private String nonSubmissionReason;
}
