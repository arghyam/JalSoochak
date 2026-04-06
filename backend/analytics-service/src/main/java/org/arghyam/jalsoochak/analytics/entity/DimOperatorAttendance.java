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

import java.time.LocalDateTime;

@Entity
@Table(name = "dim_operator_attendance_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimOperatorAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Integer tenantId;

    @Column(name = "date_key", nullable = false)
    private Integer dateKey;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "scheme_id", nullable = false)
    private Integer schemeId;

    @Column(name = "attendance", nullable = false)
    private Integer attendance;

    private String remark;

    @Column(name = "remark_by")
    private Integer remarkBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
