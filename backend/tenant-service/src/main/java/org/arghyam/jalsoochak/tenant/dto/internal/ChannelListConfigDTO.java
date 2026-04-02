package org.arghyam.jalsoochak.tenant.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.validation.ValidChannelList;

import java.util.List;

/**
 * DTO for channel list configuration.
 * Supports: BFM, ELM, PDU, IOT, MAN
 *
 * <p>{@code degraded} and {@code removedChannels} are read-time computed fields.
 * They are never stored to the database (excluded from serialization when null).
 * They are populated in GET responses when the tenant's stored channels include
 * codes that are no longer present in the system-level supported channel set.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class ChannelListConfigDTO implements ConfigValueDTO {
    @NotEmpty(message = "At least one channel must be selected")
    @Size(max = 5, message = "Maximum 5 channels allowed")
    @ValidChannelList
    private List<@NotEmpty(message = "Channel code cannot be empty") String> channels;

    /** True when one or more stored channels were removed from the system-level config. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean degraded;

    /** Channels that were in the stored config but are no longer system-supported. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> removedChannels;
}
