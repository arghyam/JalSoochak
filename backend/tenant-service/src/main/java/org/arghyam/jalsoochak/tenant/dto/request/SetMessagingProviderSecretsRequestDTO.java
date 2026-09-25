package org.arghyam.jalsoochak.tenant.dto.request;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MESSAGING-PROVIDER-SECRETS: credentials to store for one channel of one tenant.
 *
 * <p>Only the names present are written; a name left out keeps whatever it already had.
 * That is what lets a tenant replace a rotated {@code authToken} without re-sending the
 * {@code authKey}. Names are validated against the channel's own set, so a request can
 * never address a location outside the channel in its own URL.
 *
 * <p>The URL carries the tenant and the channel; the body carries names and values and
 * nothing else. There is deliberately no field naming a storage location — a
 * caller-supplied reference could point at another tenant's secret or at a platform one.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Secret values to store for a messaging channel. Write-only; never returned.")
public class SetMessagingProviderSecretsRequestDTO {

    /** Max stored length. SendGrid keys are ~69 chars and SMTP passwords far shorter; 1024 is generous. */
    public static final int MAX_SECRET_LENGTH = 1024;

    @NotEmpty(message = "secrets must contain at least one entry")
    @Schema(description = "Secret name to value. Valid names depend on the channel: "
            + "EMAIL accepts apiKey or password, SMS accepts authKey or authToken.",
            example = "{\"authKey\": \"...\", \"authToken\": \"...\"}")
    private Map<String,
            @NotBlank(message = "secret value must not be blank")
            @Size(max = MAX_SECRET_LENGTH, message = "secret value must not exceed "
                    + MAX_SECRET_LENGTH + " characters") String> secrets;

    /**
     * Names only. Lombok's generated {@code toString()} would print every value, and a
     * request DTO is rendered by debug logging and by some binding failures.
     */
    @Override
    public String toString() {
        return "SetMessagingProviderSecretsRequestDTO(secretNames="
                + (secrets == null ? "null" : secrets.keySet()) + ")";
    }
}
