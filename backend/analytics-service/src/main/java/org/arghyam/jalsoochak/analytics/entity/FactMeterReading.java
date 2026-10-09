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
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * ANOMALY-SUBMISSION-LINK (V48): the source row's {@code flow_reading_table.correlation_id}, so
     * an anomaly can be resolved to the fact row it was raised over. Not unique — the tenant column
     * has no unique constraint and the chatbot flows share one value across rows — so this is a
     * drill-down key, never a counting key.
     */
    @Column(name = "correlation_id")
    private String correlationId;

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

    /** V56: the tenant's {@code flow_reading_table.id}; null on rows from older events. */
    @Column(name = "source_reading_id")
    private Long sourceReadingId;

    /** V56: the source row's {@code updated_at}, the version this row holds. */
    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    /** V56: telemetry's pump and formula snapshot for ELM and PDU; null for BFM. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_parameters")
    private CalculationParameters calculationParameters;

    /**
     * V60: the UCUM code of the unit the reading was submitted in. The readings are in the channel's
     * standard unit, except kVAh ({@code kV.A.h}), which is stored as the meter shows it. Null means
     * the standard unit.
     *
     * @see org.arghyam.jalsoochak.analytics.enums.MeterRegister
     */
    @Column(name = "submitted_unit")
    private String submittedUnit;
}
