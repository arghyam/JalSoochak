package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemeEvent {

    private String eventType;
    private Integer schemeId;
    private Integer tenantId;
    private String schemeName;
    private Integer stateSchemeId;
    private Integer centreSchemeId;
    private Double longitude;
    private Double latitude;

    private Integer parentLgdLocationId;
    private Integer level1LgdId;
    private Integer level2LgdId;
    private Integer level3LgdId;
    private Integer level4LgdId;
    private Integer level5LgdId;
    private Integer level6LgdId;

    private Integer parentDepartmentLocationId;
    private Integer level1DeptId;
    private Integer level2DeptId;
    private Integer level3DeptId;
    private Integer level4DeptId;
    private Integer level5DeptId;
    private Integer level6DeptId;

    private Integer status;

    @JsonProperty("work_status")
    private Integer workStatus;
}
