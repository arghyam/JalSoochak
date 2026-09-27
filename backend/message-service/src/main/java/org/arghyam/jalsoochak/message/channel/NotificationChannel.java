package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.NotificationRequest;

/**
 * Strategy interface for notification delivery channels.
 * <p>
 * Each implementation handles a single delivery mechanism
 * (Webhook, Email via SendGrid, WhatsApp).
 */
public interface NotificationChannel {

    /**
     * The channel type this implementation handles.
     */
    String channelType();

    /**
     * Send a notification through this channel.
     *
     * @param request the notification payload
     * @return {@code true} if the message was accepted for delivery
     */
    boolean send(NotificationRequest request);
}
