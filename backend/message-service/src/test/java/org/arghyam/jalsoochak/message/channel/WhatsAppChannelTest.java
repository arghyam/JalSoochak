package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.channel.provider.ReportDeliveryMode;
import org.arghyam.jalsoochak.message.channel.provider.ReportSendOutcome;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendException;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendResult;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * <p>Verifies that the channel delegates correctly to {@link WhatsAppSender},
 * returns {@code true} on success, and returns {@code false} (without throwing)
 * on failure to allow the caller to handle delivery failures gracefully.</p>
 */
@ExtendWith(MockitoExtension.class)
class WhatsAppChannelTest {

    @Mock
    private WhatsAppSender whatsAppSender;

    @InjectMocks
    private WhatsAppChannel whatsAppChannel;

    // ──────────────────────────────── sendNudge ────────────────────────────────

    @Test
    void sendNudge_returnsTrueAndSendsHsm_onSuccess() {
        when(whatsAppSender.optIn("919876543210")).thenReturn(42L);

        boolean result = whatsAppChannel.sendNudge("919876543210", "Ramesh", "02 March 2026");

        assertThat(result).isTrue();
        verify(whatsAppSender).optIn("919876543210");
        verify(whatsAppSender).sendNudgeHsm(42L, "Ramesh", "02 March 2026");
    }

    @Test
    void sendNudge_returnsFalse_whenOptInThrows() {
        when(whatsAppSender.optIn(anyString()))
                .thenThrow(new RuntimeException("provider unreachable"));

        boolean result = whatsAppChannel.sendNudge("919876543210", "Ramesh", "02 March 2026");

        assertThat(result).isFalse();
        verify(whatsAppSender, never()).sendNudgeHsm(anyLong(), anyString(), anyString());
    }

    @Test
    void sendNudge_returnsFalse_whenSendNudgeHsmThrows() {
        when(whatsAppSender.optIn(anyString())).thenReturn(99L);
        doThrow(new RuntimeException("HSM send failed"))
                .when(whatsAppSender).sendNudgeHsm(anyLong(), anyString(), anyString());

        boolean result = whatsAppChannel.sendNudge("919876543210", "Op", "02 March 2026");

        assertThat(result).isFalse();
    }

    @Test
    void sendNudge_passesCorrectParametersToHsm() {
        when(whatsAppSender.optIn("911111111111")).thenReturn(55L);

        whatsAppChannel.sendNudge("911111111111", "Suresh", "03 March 2026");

        verify(whatsAppSender).sendNudgeHsm(eq(55L), eq("Suresh"), eq("03 March 2026"));
    }

    // ──────────────────────────── sendNudgeViaFlow ─────────────────────────────

    @Test
    void sendNudgeViaFlow_returnsTrueAndStartsFlow_onSuccess() {
        boolean result = whatsAppChannel.sendNudgeViaFlow(42L, "Ramesh", "02 March 2026");

        assertThat(result).isTrue();
        verify(whatsAppSender).startNudgeFlow(42L, "Ramesh", "02 March 2026");
        verify(whatsAppSender, never()).optIn(anyString());
    }

    @Test
    void sendNudgeViaFlow_returnsFalse_whenStartNudgeFlowThrows() {
        doThrow(new RuntimeException("Flow error"))
                .when(whatsAppSender).startNudgeFlow(anyLong(), anyString(), anyString());

        boolean result = whatsAppChannel.sendNudgeViaFlow(42L, "Ramesh", "02 March 2026");

        assertThat(result).isFalse();
        verify(whatsAppSender, never()).optIn(anyString());
    }

    @Test
    void sendNudgeViaFlow_passesContactIdDirectly() {
        whatsAppChannel.sendNudgeViaFlow(77L, "Suresh", "03 March 2026");

        verify(whatsAppSender).startNudgeFlow(eq(77L), eq("Suresh"), eq("03 March 2026"));
    }

    // ────────────────────────────── sendDocument ───────────────────────────────

    @Test
    void sendDocument_returnsTrueAndSendsEscalationHsm_onSuccess() {
        boolean result = whatsAppChannel.sendDocument(77L, "https://storage.example.org/report.pdf");

        assertThat(result).isTrue();
        verify(whatsAppSender).sendEscalationHsm(77L, "https://storage.example.org/report.pdf");
        verify(whatsAppSender, never()).optIn(anyString());
    }

    @Test
    void sendDocument_returnsFalse_whenSendEscalationHsmThrows() {
        doThrow(new RuntimeException("HSM delivery failed"))
                .when(whatsAppSender).sendEscalationHsm(anyLong(), anyString());

        boolean result = whatsAppChannel.sendDocument(88L, "https://storage.example.org/r2.pdf");

        assertThat(result).isFalse();
        verify(whatsAppSender, never()).optIn(anyString());
    }

    @Test
    void sendDocument_passesDocumentUrl_toEscalationHsm() {
        String documentUrl = "https://storage.example.org/escalation_L2_report.pdf";

        whatsAppChannel.sendDocument(33L, documentUrl);

        verify(whatsAppSender).sendEscalationHsm(eq(33L), eq(documentUrl));
    }

    // ────────────────────────── onboardOperator ────────────────────────────────

    @Test
    void onboardOperator_callsOptIn_updateLanguage_andStartWelcomeFlow_inOrder_andReturnsContactId() {
        when(whatsAppSender.optIn("919876543210")).thenReturn(42L);

        long contactId = whatsAppChannel.onboardOperator("919876543210", 2);

        assertThat(contactId).isEqualTo(42L);
        InOrder inOrder = inOrder(whatsAppSender);
        inOrder.verify(whatsAppSender).optIn("919876543210");
        inOrder.verify(whatsAppSender).updateContactLanguage(42L, 2);
        inOrder.verify(whatsAppSender).startWelcomeFlow(42L, null, null);
    }

    @Test
    void onboardOperator_throwsException_whenOptInFails() {
        when(whatsAppSender.optIn(anyString()))
                .thenThrow(new RuntimeException("provider unreachable"));

        assertThatThrownBy(() -> whatsAppChannel.onboardOperator("919876543210", 2))
                .isInstanceOf(RuntimeException.class);

        verify(whatsAppSender, never()).updateContactLanguage(anyLong(), anyInt());
        verify(whatsAppSender, never()).startWelcomeFlow(anyLong(), any(), any());
    }

    @Test
    void onboardOperator_throwsException_whenWelcomeFlowFails() {
        when(whatsAppSender.optIn("919876543210")).thenReturn(42L);
        doThrow(new RuntimeException("Flow error"))
                .when(whatsAppSender).startWelcomeFlow(42L, null, null);

        assertThatThrownBy(() -> whatsAppChannel.onboardOperator("919876543210", 2))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Flow error");
    }

    // ─────────────────────────── sendDailyReport ───────────────────────────────

    /**
     * Acceptance carries the provider's message id forward. That id is the only join key between a
     * report we sent and the delivery status Gupshup and Meta later report back to the provider —
     * without it the whole reconciliation is impossible, so losing it must fail a test rather than pass
     * silently.
     */
    @Test
    void sendDailyReport_returnsProvidersMessageIdOnAcceptance() {
        WhatsAppSendResult providerResult =
                new WhatsAppSendResult("241952654", "880557", ReportDeliveryMode.LINK);
        when(whatsAppSender.sendDailyReportHsm(42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER",
                LocalDate.of(2026, 8, 27), "Binod")).thenReturn(providerResult);

        ReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.result().messageId()).isEqualTo("241952654");
        assertThat(outcome.result().templateId()).isEqualTo("880557");
        assertThat(outcome.result().mode()).isEqualTo(ReportDeliveryMode.LINK);
    }

    @Test
    void sendDailyReport_normalisesABlankRoleBeforeSending() {
        when(whatsAppSender.sendDailyReportHsm(anyLong(), anyString(), eq("UNKNOWN"), any(), any()))
                .thenReturn(new WhatsAppSendResult("1", "880557", ReportDeliveryMode.LINK));

        ReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://storage.example.org/r.pdf", "  ", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isTrue();
        verify(whatsAppSender).sendDailyReportHsm(42L, "https://storage.example.org/r.pdf", "UNKNOWN",
                LocalDate.of(2026, 8, 27), "Binod");
    }

    /** A dry-run is accepted with no message id — never a placeholder that could be mistaken for real. */
    @Test
    void sendDailyReport_acceptsASuppressedSendWithoutAMessageId() {
        when(whatsAppSender.sendDailyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(WhatsAppSendResult.suppressed(ReportDeliveryMode.LINK));

        ReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.result().hasMessageId()).isFalse();
        assertThat(outcome.result().messageIdForLog()).isEqualTo("none");
    }

    @Test
    void sendDailyReport_doesNotThrow_whenProviderFails() {
        when(whatsAppSender.sendDailyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("provider unreachable"));

        ReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure().message()).contains("provider unreachable");
    }

    /**
     * The adapter's stage and error key reach the router unchanged — the two fields that tell the 20 Aug
     * media-fetch failure apart from a rejected template or receiver.
     */
    @Test
    void sendDailyReport_reportsTheStageAndErrorKeyTheProviderAssigned() {
        when(whatsAppSender.sendDailyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new WhatsAppSendException(WhatsAppSendStage.MEDIA_REGISTER, "media",
                        "(#131053) Media upload error"));

        ReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.MEDIA_REGISTER);
        assertThat(outcome.failure().errorKey()).isEqualTo("media");
        assertThat(outcome.failure().message()).isEqualTo("(#131053) Media upload error");
    }

    /** Only the provider has an error key. Our own refusal must not borrow one. */
    @Test
    void sendDailyReport_reportsNoErrorKey_forAFailureOnOurSide() {
        when(whatsAppSender.sendDailyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("requires a resolved contact id"));

        ReportSendOutcome outcome = whatsAppChannel.sendDailyReport(
                42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 27), "Binod");

        assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.CONFIG);
        assertThat(outcome.failure().errorKey()).isNull();
    }

    @Test
    void sendWeeklyReport_reportsTheStageAndErrorKeyTheProviderAssigned() {
        when(whatsAppSender.sendWeeklyReportHsm(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new WhatsAppSendException(WhatsAppSendStage.SEND, "receiver",
                        "Receiver does not exist"));

        ReportSendOutcome outcome = whatsAppChannel.sendWeeklyReport(
                42L, "https://storage.example.org/r.pdf", "SECTION_OFFICER", LocalDate.of(2026, 8, 24), "Binod");

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.SEND);
        assertThat(outcome.failure().errorKey()).isEqualTo("receiver");
    }

    // ───────────────────── failure-stage classification ────────────────────────

    /**
     * Which provider call failed is the adapter's knowledge, so the channel takes the stage as given.
     * The adapter tests pin which failure carries which stage.
     */
    @ParameterizedTest
    @EnumSource(value = WhatsAppSendStage.class, names = {"MEDIA_REGISTER", "SEND", "SEND_NO_MESSAGE_ID"})
    void stageOf_takesTheStageTheProviderAssigned(WhatsAppSendStage stage) {
        assertThat(WhatsAppChannel.stageOf(new WhatsAppSendException(stage, null, "provider failure")))
                .isEqualTo(stage);
    }

    /**
     * A block timeout is an IllegalStateException, so it has to be matched before the configuration
     * branch. It is the one failure a retry makes worse — the provider may already have sent the message.
     */
    @Test
    void stageOf_tagsABlockTimeoutBeforeTreatingItAsConfiguration() {
        assertThat(WhatsAppChannel.stageOf(
                new IllegalStateException("Timeout on blocking read for 30000 MILLISECONDS")))
                .isEqualTo(WhatsAppSendStage.TIMEOUT);
    }

    @Test
    void stageOf_findsATimeoutNestedInACause() {
        RuntimeException wrapped = new RuntimeException("send failed",
                new IllegalStateException("Timeout on blocking read for 30000 MILLISECONDS"));

        assertThat(WhatsAppChannel.stageOf(wrapped)).isEqualTo(WhatsAppSendStage.TIMEOUT);
    }

    @Test
    void stageOf_tagsOurOwnConfigurationAndInputErrors() {
        assertThat(WhatsAppChannel.stageOf(new IllegalArgumentException("requires a resolved contact id")))
                .isEqualTo(WhatsAppSendStage.CONFIG);
        assertThat(WhatsAppChannel.stageOf(new IllegalStateException("does not start with the prefix")))
                .isEqualTo(WhatsAppSendStage.CONFIG);
    }

    @Test
    void stageOf_fallsBackToSendForAnythingElse() {
        assertThat(WhatsAppChannel.stageOf(new RuntimeException("who knows")))
                .isEqualTo(WhatsAppSendStage.SEND);
    }

    // ─────────────────────────── channelType ───────────────────────────────────

    @Test
    void channelType_returnsWhatsApp() {
        assertThat(whatsAppChannel.channelType()).isEqualTo("WHATSAPP");
    }
}
