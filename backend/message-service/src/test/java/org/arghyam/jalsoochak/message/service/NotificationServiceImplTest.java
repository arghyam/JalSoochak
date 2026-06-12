package org.arghyam.jalsoochak.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.arghyam.jalsoochak.message.channel.NotificationChannel;
import org.arghyam.jalsoochak.message.dto.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationServiceImpl}.
 *
 * <p>Verifies that the service builds its channel map correctly from the
 * injected list, routes requests to the right channel by type, defaults
 * to WEBHOOK when the channel field is null, and returns meaningful status
 * strings for supported and unsupported channels.</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationChannel whatsAppChannel;

    @Mock
    private NotificationChannel webhookChannel;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        when(whatsAppChannel.channelType()).thenReturn("WHATSAPP");
        when(webhookChannel.channelType()).thenReturn("WEBHOOK");
        notificationService = new NotificationServiceImpl(List.of(whatsAppChannel, webhookChannel));
        // Clear the channelType() calls made during construction so that
        // verifyNoInteractions() only reflects interactions from the test itself.
        clearInvocations(whatsAppChannel, webhookChannel);
    }

    // ─────────────────────── channel routing ───────────────────────────────────

    @Test
    void send_routesToWhatsApp_andReturnsSentStatus_onSuccess() {
        when(whatsAppChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel("WHATSAPP")
                .recipient("919876543210")
                .body("You have not submitted today's reading.")
                .build();

        String result = notificationService.send(request);

        assertThat(result).contains("WHATSAPP").contains("SENT");
        verify(whatsAppChannel).send(request);
        verifyNoInteractions(webhookChannel);
    }

    @Test
    void send_routesToWebhook_andReturnsSentStatus_onSuccess() {
        when(webhookChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel("WEBHOOK")
                .recipient("https://webhook.example.com/notify")
                .body("{\"event\":\"nudge\"}")
                .build();

        String result = notificationService.send(request);

        assertThat(result).contains("WEBHOOK").contains("SENT");
        verify(webhookChannel).send(request);
        verifyNoInteractions(whatsAppChannel);
    }

    @Test
    void send_isCaseInsensitive_forChannelType() {
        when(whatsAppChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel("whatsapp")
                .recipient("919876543210")
                .build();

        notificationService.send(request);

        verify(whatsAppChannel).send(request);
        verifyNoInteractions(webhookChannel);
    }

    @Test
    void send_isCaseInsensitive_forMixedCaseChannelType() {
        when(webhookChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel("WebHook")
                .recipient("https://hook.example.com")
                .build();

        notificationService.send(request);

        verify(webhookChannel).send(request);
    }

    // ──────────────────────── failure / FAILED status ──────────────────────────

    @Test
    void send_returnsFailedStatus_whenChannelReturnsFalse() {
        when(whatsAppChannel.send(any())).thenReturn(false);
        NotificationRequest request = NotificationRequest.builder()
                .channel("WHATSAPP")
                .recipient("919876543210")
                .build();

        String result = notificationService.send(request);

        assertThat(result).contains("WHATSAPP").contains("FAILED");
    }

    // ────────────────────── null channel → default WEBHOOK ─────────────────────

    @Test
    void send_defaultsToWebhook_whenChannelIsNull() {
        when(webhookChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel(null)
                .recipient("https://hook.example.com")
                .build();

        String result = notificationService.send(request);

        assertThat(result).contains("WEBHOOK").contains("SENT");
        verify(webhookChannel).send(request);
        verifyNoInteractions(whatsAppChannel);
    }

    // ─────────────────────── unsupported channel ───────────────────────────────

    @Test
    void send_returnsErrorMessage_forUnsupportedChannelType() {
        NotificationRequest request = NotificationRequest.builder()
                .channel("EMAIL")
                .recipient("user@example.com")
                .build();

        String result = notificationService.send(request);

        assertThat(result).contains("Unsupported notification channel").contains("EMAIL");
        verifyNoInteractions(whatsAppChannel, webhookChannel);
    }

    @Test
    void send_returnsErrorMessage_thatListsAvailableChannels() {
        NotificationRequest request = NotificationRequest.builder()
                .channel("SMS")
                .recipient("919876543210")
                .build();

        String result = notificationService.send(request);

        assertThat(result).containsAnyOf("WHATSAPP", "WEBHOOK");
    }

    // ─────────────────────── passes request unchanged ──────────────────────────

    @Test
    void send_passesTheOriginalRequestObjectToChannel() {
        when(whatsAppChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel("WHATSAPP")
                .recipient("919876543210")
                .subject("Reminder")
                .body("Please submit your reading.")
                .date("16 April 2026")
                .build();

        notificationService.send(request);

        verify(whatsAppChannel).send(request);
    }

    // ─────────────────────── channel map deduplication ─────────────────────────

    @Test
    void send_onlyInvokesOneChannel_evenWhenMultipleChannelsRegistered() {
        when(whatsAppChannel.send(any())).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .channel("WHATSAPP")
                .build();

        notificationService.send(request);

        verify(whatsAppChannel, times(1)).send(any());
        verifyNoInteractions(webhookChannel);
    }
}
