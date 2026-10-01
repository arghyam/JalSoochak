package org.arghyam.jalsoochak.telemetry.channel;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@link ReadingChannel} a user submits readings through, from their
 * stored preference in {@code <tenant schema>.user_channel_preference}. Falls back to
 * {@link ReadingChannel#DEFAULT BFM} when no preference exists or the inputs are
 * missing, so reading submissions keep working exactly as before.
 */
@Service
@RequiredArgsConstructor
public class ReadingChannelResolver {

    private final UserChannelPreferenceRepository userChannelPreferenceRepository;

    public ReadingChannel resolve(String schemaName, String contactId) {
        if (schemaName == null || contactId == null || contactId.isBlank()) {
            return ReadingChannel.DEFAULT;
        }
        return userChannelPreferenceRepository.findChannelValue(schemaName, contactId)
                .map(ReadingChannel::fromChannelValue)
                .orElse(ReadingChannel.DEFAULT);
    }

    /** Convenience for producers that attach the numeric channel code to an event. */
    public int resolveCode(String schemaName, String contactId) {
        return resolve(schemaName, contactId).getCode();
    }
}
