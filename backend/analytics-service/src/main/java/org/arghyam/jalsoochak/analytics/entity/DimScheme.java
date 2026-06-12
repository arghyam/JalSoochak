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
@Table(name = "dim_scheme_table", schema = "analytics_schema")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "scheme_id", nullable = false)
    private Integer schemeId;

    @Column(name = "tenant_id", nullable = false)
    private Integer tenantId;

    @Column(name = "scheme_name")
    private String schemeName;

    @Column(name = "state_scheme_id", nullable = false)
    private Integer stateSchemeId;

    @Column(name = "centre_scheme_id", nullable = false)
    private Integer centreSchemeId;

    private Double longitude;
    private Double latitude;

    @Column(name = "parent_lgd_location_id", nullable = false)
    private Integer parentLgdLocationId;

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

    @Column(name = "parent_department_location_id", nullable = false)
    private Integer parentDepartmentLocationId;

    @Column(name = "level_1_dept_id", nullable = false)
    private Integer level1DeptId;

    @Column(name = "level_2_dept_id", nullable = false)
    private Integer level2DeptId;

    @Column(name = "level_3_dept_id", nullable = false)
    private Integer level3DeptId;

    @Column(name = "level_4_dept_id", nullable = false)
    private Integer level4DeptId;

    @Column(name = "level_5_dept_id", nullable = false)
    private Integer level5DeptId;

    @Column(name = "level_6_dept_id", nullable = false)
    private Integer level6DeptId;

    @Column(name = "operating_status")
    private Integer operatingStatus;

    @Column(name = "work_status")
    private Integer workStatus;

    @Column(name = "fhtc_count")
    private Integer achievedFhtcCount;

    @Column(name = "planned_fhtc")
    private Integer plannedFhtcCount;

    @Column(name = "house_hold_count")
    private Integer houseHoldCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
