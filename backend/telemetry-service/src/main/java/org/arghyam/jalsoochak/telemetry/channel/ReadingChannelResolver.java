package org.arghyam.jalsoochak.telemetry.channel;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the {@link ReadingChannel} a user submits readings through.
 *
 * <ol>
 *   <li>A tenant that supports only one channel ({@link TenantSupportedChannels#soleChannel}) reads
 *       everything on it. Its chatbot hides the "change channel" item, so its operators never pick a
 *       channel, and a preference saved before the tenant narrowed its list can't be changed either.</li>
 *   <li>Otherwise the user's stored preference in {@code <tenant schema>.user_channel_preference}.</li>
 *   <li>Otherwise {@link ReadingChannel#DEFAULT BFM}, also when the schema or contact is missing.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ReadingChannelResolver {

    private final UserChannelPreferenceRepository userChannelPreferenceRepository;
    private final TenantSupportedChannels tenantSupportedChannels;

    public ReadingChannel resolve(Integer tenantId, String schemaName, String contactId) {
        Optional<ReadingChannel> soleChannel = tenantSupportedChannels.soleChannel(tenantId);
        if (soleChannel.isPresent()) {
            return soleChannel.get();
        }
        if (schemaName == null || contactId == null || contactId.isBlank()) {
            return ReadingChannel.DEFAULT;
        }
        return userChannelPreferenceRepository.findChannelValue(schemaName, contactId)
                .map(ReadingChannel::fromChannelValue)
                .orElse(ReadingChannel.DEFAULT);
    }
}
