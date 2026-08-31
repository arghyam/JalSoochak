package org.arghyam.jalsoochak.message.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.InOrder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WhatsAppChannel}.
 *
 * <p>Verifies that the channel delegates correctly to {@link GlificWhatsAppService},
 * returns {@code true} on success, and returns {@code false} (without throwing)
 * on failure to allow the caller to handle delivery failures gracefully.</p>
 */
@ExtendWith(MockitoExtension.class)
class WhatsAppChannelTest {

    @Mock
    private GlificWhatsAppService glificWhatsAppService;

    @InjectMocks
    private WhatsAppChannel whatsAppChannel;

    // ──────────────────────────────── sendNudge ────────────────────────────────

    @Test
    void sendNudge_returnsTrueAndSendsHsm_onSuccess() {
        when(glificWhatsAppService.optIn("919876543210")).thenReturn(42L);

        boolean result = whatsAppChannel.sendNudge("919876543210", "Ramesh", "02 March 2026");

        assertThat(result).isTrue();
        verify(glificWhatsAppService).optIn("919876543210");
        verify(glificWhatsAppService).sendNudgeHsm(42L, "Ramesh", "02 March 2026");
    }

    @Test
    void sendNudge_returnsFalse_whenOptInThrows() {
        when(glificWhatsAppService.optIn(anyString()))
                .thenThrow(new RuntimeException("Glific unreachable"));

        boolean result = whatsAppChannel.sendNudge("919876543210", "Ramesh", "02 March 2026");

        assertThat(result).isFalse();
        verify(glificWhatsAppService, never()).sendNudgeHsm(anyLong(), anyString(), anyString());
    }

    @Test
    void sendNudge_returnsFalse_whenSendNudgeHsmThrows() {
        when(glificWhatsAppService.optIn(anyString())).thenReturn(99L);
        doThrow(new RuntimeException("HSM send failed"))
                .when(glificWhatsAppService).sendNudgeHsm(anyLong(), anyString(), anyString());

        boolean result = whatsAppChannel.sendNudge("919876543210", "Op", "02 March 2026");

        assertThat(result).isFalse();
    }

    @Test
    void sendNudge_passesCorrectParametersToHsm() {
        when(glificWhatsAppService.optIn("911111111111")).thenReturn(55L);

        whatsAppChannel.sendNudge("911111111111", "Suresh", "03 March 2026");

        verify(glificWhatsAppService).sendNudgeHsm(eq(55L), eq("Suresh"), eq("03 March 2026"));
    }

    // ──────────────────────────── sendNudgeViaFlow ─────────────────────────────

    @Test
    void sendNudgeViaFlow_returnsTrueAndStartsFlow_onSuccess() {
        boolean result = whatsAppChannel.sendNudgeViaFlow(42L, "Ramesh", "02 March 2026");

        assertThat(result).isTrue();
        verify(glificWhatsAppService).startNudgeFlow(42L, "Ramesh", "02 March 2026");
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void sendNudgeViaFlow_returnsFalse_whenStartNudgeFlowThrows() {
        doThrow(new RuntimeException("Flow error"))
                .when(glificWhatsAppService).startNudgeFlow(anyLong(), anyString(), anyString());

        boolean result = whatsAppChannel.sendNudgeViaFlow(42L, "Ramesh", "02 March 2026");

        assertThat(result).isFalse();
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void sendNudgeViaFlow_passesContactIdDirectly() {
        whatsAppChannel.sendNudgeViaFlow(77L, "Suresh", "03 March 2026");

        verify(glificWhatsAppService).startNudgeFlow(eq(77L), eq("Suresh"), eq("03 March 2026"));
    }

    // ────────────────────────────── sendDocument ───────────────────────────────

    @Test
    void sendDocument_returnsTrueAndSendsEscalationHsm_onSuccess() {
        boolean result = whatsAppChannel.sendDocument(77L, "https://minio.example.com/report.pdf");

        assertThat(result).isTrue();
        verify(glificWhatsAppService).sendEscalationHsm(77L, "https://minio.example.com/report.pdf");
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void sendDocument_returnsFalse_whenSendEscalationHsmThrows() {
        doThrow(new RuntimeException("HSM delivery failed"))
                .when(glificWhatsAppService).sendEscalationHsm(anyLong(), anyString());

        boolean result = whatsAppChannel.sendDocument(88L, "https://minio.example.com/r2.pdf");

        assertThat(result).isFalse();
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void sendDocument_passesMinioUrl_toEscalationHsm() {
        String minioUrl = "https://minio.example.com/escalation_L2_report.pdf";

        whatsAppChannel.sendDocument(33L, minioUrl);

        verify(glificWhatsAppService).sendEscalationHsm(eq(33L), eq(minioUrl));
    }

    // ────────────────────────── onboardOperator ────────────────────────────────

    @Test
    void onboardOperator_callsOptIn_updateLanguage_andStartWelcomeFlow_inOrder_andReturnsContactId() {
        when(glificWhatsAppService.optIn("919876543210")).thenReturn(42L);

        long contactId = whatsAppChannel.onboardOperator("919876543210", 2);

        assertThat(contactId).isEqualTo(42L);
        InOrder inOrder = inOrder(glificWhatsAppService);
        inOrder.verify(glificWhatsAppService).optIn("919876543210");
        inOrder.verify(glificWhatsAppService).updateContactLanguage(42L, 2);
        inOrder.verify(glificWhatsAppService).startWelcomeFlow(42L, null, null);
    }

    @Test
    void onboardOperator_throwsException_whenOptInFails() {
        when(glificWhatsAppService.optIn(anyString()))
                .thenThrow(new RuntimeException("Glific unreachable"));

        assertThatThrownBy(() -> whatsAppChannel.onboardOperator("919876543210", 2))
                .isInstanceOf(RuntimeException.class);

        verify(glificWhatsAppService, never()).updateContactLanguage(anyLong(), anyInt());
        verify(glificWhatsAppService, never()).startWelcomeFlow(anyLong(), any(), any());
    }

    @Test
    void onboardOperator_throwsException_whenWelcomeFlowFails() {
        when(glificWhatsAppService.optIn("919876543210")).thenReturn(42L);
        doThrow(new RuntimeException("Flow error"))
                .when(glificWhatsAppService).startWelcomeFlow(42L, null, null);

        assertThatThrownBy(() -> whatsAppChannel.onboardOperator("919876543210", 2))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Flow error");
    }

    // ─────────────────────────── sendDailyReport ───────────────────────────────

    /**
     * Acceptance carries Glific's message id forward. That id is the only join key between a report we
     * sent and the delivery status Gupshup and Meta later report back to Glific — without it the whole
     * reconciliation is impossible, so losing it must fail a test rather than pass silently.
     */
    @Test
    void sendDailyReport_returnsGlificsMessageIdOnAcceptance() {
        GlificSendResult glificResult =
                new GlificSendResult("241952654", "880557", DailyReportDeliveryMode.LINK);
        when(glificWhatsAppService.sendDailyReportHsm(42L, "https://minio/r.pdf", "SECTION_OFFICER",
                LocalDate.of(2026, 8, 27), "Binod")).thenReturn(glificResult);

        DailyReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://minio/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.result().messageId()).isEqualTo("241952654");
        assertThat(outcome.result().templateId()).isEqualTo("880557");
        assertThat(outcome.result().mode()).isEqualTo(DailyReportDeliveryMode.LINK);
    }

    @Test
    void sendDailyReport_normalisesABlankRoleBeforeSending() {
        when(glificWhatsAppService.sendDailyReportHsm(anyLong(), anyString(), eq("UNKNOWN"), any(), any()))
                .thenReturn(new GlificSendResult("1", "880557", DailyReportDeliveryMode.LINK));

        DailyReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://minio/r.pdf", "  ", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isTrue();
        verify(glificWhatsAppService).sendDailyReportHsm(42L, "https://minio/r.pdf", "UNKNOWN",
                LocalDate.of(2026, 8, 27), "Binod");
    }

    /** A dry-run is accepted with no message id — never a placeholder that could be mistaken for real. */
    @Test
    void sendDailyReport_acceptsASuppressedSendWithoutAMessageId() {
        when(glificWhatsAppService.sendDailyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(GlificSendResult.suppressed(DailyReportDeliveryMode.LINK));

        DailyReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://minio/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.result().hasMessageId()).isFalse();
        assertThat(outcome.result().messageIdForLog()).isEqualTo("none");
    }

    @Test
    void sendDailyReport_doesNotThrow_whenGlificFails() {
        when(glificWhatsAppService.sendDailyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Glific unreachable"));

        DailyReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://minio/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure().message()).contains("Glific unreachable");
    }

    // ───────────────────── failure-stage classification ────────────────────────

    /**
     * The 20 Aug 2026 incident: Meta could not fetch the MinIO PDF and {@code createMessageMedia}
     * rejected the send. That needs a different fix from a rejected message, and used to be
     * indistinguishable in the logs.
     */
    @Test
    void stageOf_tagsAMediaRegistrationFailure() {
        assertThat(WhatsAppChannel.stageOf(new GlificMutationException("createMessageMedia", "media",
                "(#131053) Media upload error"))).isEqualTo(GlificSendStage.MEDIA_REGISTER);
    }

    /**
     * A send Glific accepted but returned no id for. It subclasses {@link GlificMutationException}, so
     * it would otherwise be swept into {@link GlificSendStage#SEND} and retried — sending the officer a
     * second copy of a report Glific already holds.
     */
    @Test
    void stageOf_tagsAnAcceptedSendThatReturnedNoMessageId() {
        assertThat(WhatsAppChannel.stageOf(new GlificMissingMessageIdException("sendHsmMessage")))
                .isEqualTo(GlificSendStage.SEND_NO_MESSAGE_ID);
    }

    @Test
    void stageOf_tagsARejectedSend() {
        assertThat(WhatsAppChannel.stageOf(new GlificMutationException("sendHsmMessage", "receiver",
                "Receiver does not exist"))).isEqualTo(GlificSendStage.SEND);
        assertThat(WhatsAppChannel.stageOf(new GlificMutationException("createAndSendMessage", null,
                "boom"))).isEqualTo(GlificSendStage.SEND);
    }

    /**
     * A block timeout is an IllegalStateException, so it has to be matched before the configuration
     * branch. It is the one failure a retry makes worse — Glific may already have sent the message.
     */
    @Test
    void stageOf_tagsABlockTimeoutBeforeTreatingItAsConfiguration() {
        assertThat(WhatsAppChannel.stageOf(
                new IllegalStateException("Timeout on blocking read for 30000 MILLISECONDS")))
                .isEqualTo(GlificSendStage.TIMEOUT);
    }

    @Test
    void stageOf_findsATimeoutNestedInACause() {
        RuntimeException wrapped = new RuntimeException("send failed",
                new IllegalStateException("Timeout on blocking read for 30000 MILLISECONDS"));

        assertThat(WhatsAppChannel.stageOf(wrapped)).isEqualTo(GlificSendStage.TIMEOUT);
    }

    @Test
    void stageOf_tagsOurOwnConfigurationAndInputErrors() {
        assertThat(WhatsAppChannel.stageOf(new IllegalArgumentException("requires a resolved contact id")))
                .isEqualTo(GlificSendStage.CONFIG);
        assertThat(WhatsAppChannel.stageOf(new IllegalStateException("does not start with the prefix")))
                .isEqualTo(GlificSendStage.CONFIG);
    }

    @Test
    void stageOf_fallsBackToSendForAnythingElse() {
        assertThat(WhatsAppChannel.stageOf(new RuntimeException("who knows")))
                .isEqualTo(GlificSendStage.SEND);
    }

    // ─────────────────────────── channelType ───────────────────────────────────

    @Test
    void channelType_returnsWhatsApp() {
        assertThat(whatsAppChannel.channelType()).isEqualTo("WHATSAPP");
    }
}
