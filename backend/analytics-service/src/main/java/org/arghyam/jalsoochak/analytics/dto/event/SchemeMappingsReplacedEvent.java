package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * A scheme's full list of villages and sub-divisions, sent whenever they change, together with its
 * details. The scheme gets one {@code dim_scheme_table} row per village and sub-division pair. An empty
 * list means the scheme has none; an absent one means the message is malformed.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemeMappingsReplacedEvent extends SchemeEvent {

    private List<Location> villages;
    private List<Location> subDivisions;

    /**
     * A village or sub-division, with the ids of its level 1 to 6 ancestors. Its own id sits at its own
     * level; the levels below it are null.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {
        private Integer id;
        private Integer level1Id;
        private Integer level2Id;
        private Integer level3Id;
        private Integer level4Id;
        private Integer level5Id;
        private Integer level6Id;
    }
}
