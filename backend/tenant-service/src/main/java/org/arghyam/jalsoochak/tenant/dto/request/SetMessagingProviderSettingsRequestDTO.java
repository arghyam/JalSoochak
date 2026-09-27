package org.arghyam.jalsoochak.tenant.dto.request;

import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.UnknownPropertyGuard;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MESSAGING-PROVIDER-SETTINGS: the provider settings to store for a tenant.
 *
 * <p>Both channels are optional and independent, so one call can configure a tenant end to end or
 * change a single channel. A channel left out is untouched — it is not cleared. Clearing is
 * {@code DELETE /messaging-providers/{channel}}, which is a separate, deliberate act because it
 * moves the tenant back to the platform's own provider.
 *
 * <p>An empty body is rejected rather than treated as a no-op: it is always a mistake, and a silent
 * 200 would look like the settings were applied.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Provider settings for a tenant's own email and SMS accounts. Never contains credentials.")
public class SetMessagingProviderSettingsRequestDTO {

    @Valid
    @Schema(description = "Email provider settings; omit to leave the channel as it is")
    private EmailProviderConfigDTO email;

    @Valid
    @Schema(description = "SMS provider settings; omit to leave the channel as it is")
    private SmsProviderConfigDTO sms;

    /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        UnknownPropertyGuard.reject(property, "the messaging provider settings request");
    }

    public boolean isEmpty() {
        return email == null && sms == null;
    }
}
