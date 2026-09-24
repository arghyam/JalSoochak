package org.arghyam.jalsoochak.telemetry.provider.whatsapp;

import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;

/**
 * Hands the outcome of an asynchronously processed meter image back to the operator's WhatsApp
 * conversation, which waits on it before replying.
 */
public interface ConversationResumeGateway {

    /**
     * Resumes the operator's paused readings conversation with {@code result}. Failures are logged,
     * not thrown: by this point the reading has already been recorded or rejected, and the caller
     * is a background task with no one left to tell. Does nothing when resuming is disabled.
     *
     * @param contactId the operator's phone number, as the inbound webhook carried it
     * @param jobId     the id the webhook was acknowledged with, echoed back to the conversation
     * @param result    the processing outcome; {@code null} is sent as an unsuccessful result
     */
    void resumeReadingsFlow(String contactId, String jobId, CreateReadingResponse result);
}
