package org.arghyam.jalsoochak.telemetry.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The reading channels a tenant supports, from its {@code TENANT_SUPPORTED_CHANNELS} config: a JSON
 * object {@code {"channels": [...]}}, a bare array, or a single string, as the chatbot's channel menu
 * reads it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantSupportedChannels {

    static final String CONFIG_KEY = "TENANT_SUPPORTED_CHANNELS";

    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;

    /**
     * The tenant's only channel, when its config lists exactly one (ignoring case and repeats) and that
     * entry is a canonical channel code. Such a tenant's chatbot hides the "change channel" item, so an
     * operator there can never pick a channel. Empty for a missing tenant id, a missing or unreadable
     * config, several channels, or an entry that isn't a code.
     */
    public Optional<ReadingChannel> soleChannel(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        Set<String> channels = configuredChannels(tenantId);
        if (channels.size() != 1) {
            return Optional.empty();
        }
        return ReadingChannel.parseStrict(channels.iterator().next());
    }

    private Set<String> configuredChannels(Integer tenantId) {
        Set<String> channels = new LinkedHashSet<>();
        Optional<String> raw = tenantConfigRepository.findConfigValue(tenantId, CONFIG_KEY)
                .filter(value -> !value.isBlank());
        if (raw.isEmpty()) {
            return channels;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw.get());
        } catch (Exception e) {
            log.warn("Invalid {} JSON for tenantId {}: {}", CONFIG_KEY, tenantId, e.getMessage());
            return channels;
        }
        JsonNode channelsNode = node != null && node.has("channels") ? node.get("channels") : node;
        if (channelsNode == null) {
            return channels;
        }
        if (channelsNode.isArray()) {
            channelsNode.forEach(child -> addChannel(channels, child));
        } else {
            addChannel(channels, channelsNode);
        }
        return channels;
    }

    private static void addChannel(Set<String> channels, JsonNode node) {
        if (node != null && node.isTextual() && !node.asText().isBlank()) {
            channels.add(node.asText().trim().toUpperCase(Locale.ROOT));
        }
    }
}
