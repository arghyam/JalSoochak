package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChildRegionDetails {

    private Integer lgdId;
    private Integer departmentId;
    private Integer parentLgdId;
    private Integer parentDepartmentId;
    private Integer lgdLevel;
    private Integer schemeCount;
    private String title;
    private String lgdCode;
}
