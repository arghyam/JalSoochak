package org.arghyam.jalsoochak.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Geometry;

import java.time.LocalDateTime;

/**
 * {@code is_onboarded} (V62) is deliberately not mapped: ops set it with SQL, and every {@code save()}
 * writes all mapped columns, which would overwrite it. Read it through {@code RegionOnboardingRepository}.
 */
@Entity
@Table(name = "dim_lgd_location_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimLgdLocation {

    @Id
    @Column(name = "lgd_id")
    private Integer lgdId;

    @Column(name = "tenant_id", nullable = false)
    private Integer tenantId;

    @Column(name = "lgd_code")
    private String lgdCode;

    @Column(name = "lgd_c_name")
    private String lgdCName;

    private String title;

    @Column(name = "lgd_level", nullable = false)
    private Integer lgdLevel;

    @Column(name = "level_1_lgd_id", nullable = false)
    private Integer level1LgdId;

    @Column(name = "level_2_lgd_id", nullable = false)
    private Integer level2LgdId;

    @Column(name = "level_3_lgd_id", nullable = false)
    private Integer level3LgdId;

    @Column(name = "level_4_lgd_id", nullable = false)
    private Integer level4LgdId;

    @Column(name = "level_5_lgd_id", nullable = false)
    private Integer level5LgdId;

    @Column(name = "level_6_lgd_id", nullable = false)
    private Integer level6LgdId;

    @Column(columnDefinition = "geometry")
    private Geometry geom;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
