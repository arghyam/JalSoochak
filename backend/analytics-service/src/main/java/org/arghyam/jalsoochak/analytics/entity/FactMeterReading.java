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
import java.time.LocalDateTime;

@Entity
@Table(name = "fact_meter_reading_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactMeterReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Integer tenantId;

    @Column(name = "scheme_id", nullable = false)
    private Integer schemeId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    /**
     * Readings are {@code BigDecimal} over a bare {@code NUMERIC} column, mirroring
     * {@code flow_reading_table} — the meters have a decimal digit and the warehouse keeps it.
     *
     * <p>No precision or scale is declared: the column is unconstrained, as at the source, and this
     * entity must not impose one it does not have.
     */
    @Column(name = "extracted_reading", nullable = false)
    private BigDecimal extractedReading;

    @Column(name = "confirmed_reading", nullable = false)
    private BigDecimal confirmedReading;

    private Integer confidence;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "reading_at", nullable = false)
    private LocalDateTime readingAt;

    private Integer channel;

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @Column(name = "submission_status")
    private Integer submissionStatus;

    @Column(name = "reading_type", nullable = false)
    private Integer readingType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
