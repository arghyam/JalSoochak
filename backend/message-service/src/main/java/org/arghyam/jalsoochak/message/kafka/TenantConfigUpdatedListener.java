package org.arghyam.jalsoochak.message.kafka;

import java.util.LinkedHashSet;
import java.util.Set;

import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: evicts a tenant's cached provider when tenant-service reports that its
 * settings or secrets changed (O2-10).
 *
 * <p>{@code tenant-service-topic} is a topic message-service does not otherwise consume:
 * {@link KafkaConsumer} listens only to {@code common-topic}. It is subscribed here through
 * {@link KafkaConfig#tenantEventListenerContainerFactory} rather than the default factory, because
 * this listener needs the opposite of everything that factory provides:
 *
 * <ul>
 *   <li><b>A unique group id per instance</b>, so every replica evicts. With the shared group id a
 *       config change would reach exactly one replica and the others would serve a tenant's old
 *       provider until the TTL expired. telemetry-service's listener on this topic does use the
 *       shared id; it is single-replica today, and that part is deliberately not copied.</li>
 *   <li><b>{@code auto-offset-reset: latest}</b>, because a brand-new group id on a topic with
 *       retention would otherwise replay every tenant event ever published on the first poll, to
 *       evict caches that are empty.</li>
 *   <li><b>No retries and no dead-letter topic.</b> An eviction that fails is answered by the TTL,
 *       and a permanently failing record must not be republished to a DLT nobody reads.</li>
 * </ul>
 *
 * <p>Only the two provider settings keys matter. tenant-service publishes
 * {@code TENANT_CONFIG_UPDATED} for every config write, most of which this service does not cache,
 * so an event naming unrelated keys is ignored rather than clearing a cache it has no reason to.
 * A secret write raises the same event under its channel's settings key, which is why the secret
 * store needs no event of its own (§4).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantConfigUpdatedListener {

    static final String TENANT_TOPIC = "tenant-service-topic";
    private static final String EVENT_TYPE = "TENANT_CONFIG_UPDATED";

    private final TenantChannelProviders channelProviders;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = TENANT_TOPIC,
            groupId = "#{'message-service-provider-cache-' + T(java.util.UUID).randomUUID()}",
            containerFactory = "tenantEventListenerContainerFactory")
    public void onTenantEvent(String message) {
        log.debug("[Providers] Received message from {}: {}", TENANT_TOPIC, message);
        try {
            JsonNode event = objectMapper.readTree(message);
            if (!EVENT_TYPE.equals(event.path("eventType").asText(null))) {
                return;
            }
            JsonNode tenantIdNode = event.get("tenantId");
            if (tenantIdNode == null || !tenantIdNode.canConvertToInt()) {
                log.warn("[Providers] {} carries no usable tenantId; ignoring", EVENT_TYPE);
                return;
            }
            int tenantId = tenantIdNode.asInt();

            Set<MessagingChannel> channels = channelsIn(event.path("configKeys"));
            if (channels.isEmpty()) {
                return;
            }
            for (MessagingChannel channel : channels) {
                channelProviders.evict(tenantId, channel);
            }
        } catch (Exception e) {
            // Swallowed on purpose. A failure here costs at most one stale cache entry until the
            // TTL expires, and this listener has no retry or dead-letter path to escalate to; an
            // exception escaping would only make the container re-deliver the same unparseable
            // record. The payload is not logged: tenant events are not PII, but this handler must
            // stay cheap enough to run on every config write in the platform.
            log.warn("[Providers] Failed to process a {} message: {}: {}", TENANT_TOPIC,
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static Set<MessagingChannel> channelsIn(JsonNode configKeys) {
        Set<MessagingChannel> channels = new LinkedHashSet<>();
        if (configKeys == null || !configKeys.isArray()) {
            return channels;
        }
        for (JsonNode key : configKeys) {
            MessagingChannel channel = MessagingChannel.forSettingsConfigKey(key.asText(null));
            if (channel != null) {
                channels.add(channel);
            }
        }
        return channels;
    }
}
