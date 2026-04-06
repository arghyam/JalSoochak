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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "anomaly_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Anomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, unique = true)
    private String uuid;

    @Column(name = "type", nullable = false, length = 128)
    private String type;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "scheme_id")
    private Integer schemeId;

    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "ai_reading")
    private BigDecimal aiReading;

    @Column(name = "ai_confidence_percentage")
    private BigDecimal aiConfidencePercentage;

    @Column(name = "overridden_reading")
    private BigDecimal overriddenReading;

    @Column(name = "retries")
    private Integer retries;

    @Column(name = "previous_reading")
    private BigDecimal previousReading;

    @Column(name = "previous_reading_date")
    private LocalDate previousReadingDate;

    @Column(name = "consecutive_days_missed")
    private Integer consecutiveDaysMissed;

    @Column(name = "reason")
    private String reason;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "resolved_by")
    private Integer resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "deleted_by")
    private Integer deletedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
