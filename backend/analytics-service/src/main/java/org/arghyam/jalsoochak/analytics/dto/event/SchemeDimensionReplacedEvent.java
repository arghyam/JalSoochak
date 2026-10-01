package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * {@code SCHEME_DIMENSION_REPLACED} from scheme-service: a scheme's attributes plus <b>every</b>
 * location row it should have in {@code dim_scheme_table} — one per village × sub-division it is mapped
 * to (or one under the state when it has no village). Analytics cannot read tenant schemas, so the
 * sender works out the fan-out and each row's ancestor levels.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemeDimensionReplacedEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private String schemeName;
    private Integer stateSchemeId;
    private Integer centreSchemeId;
    private Double latitude;
    private Double longitude;
    private Integer operatingStatus;
    private Integer workStatus;
    private Integer fhtcCount;
    private Integer plannedFhtc;
    private Integer houseHoldCount;
    /**
     * {@code true} when {@code rows} is the scheme's complete, current location set, so an empty list
     * means "no locations": its stale rows are deleted. Absent or {@code false}: an empty list means
     * "locations not sent", and only the attributes are realigned.
     */
    private Boolean locationsKnown;
    private List<Row> rows;

    /**
     * One location the scheme sits at. {@code lgdLevels} / {@code deptLevels} hold the ancestor id at
     * levels 1..6 (index 0 = level 1), {@code null} where the tree has no node at that level.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Row {
        private Integer parentLgdLocationId;
        private List<Integer> lgdLevels;
        private Integer parentDepartmentLocationId;
        private List<Integer> deptLevels;
    }
}
