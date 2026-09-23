package org.arghyam.jalsoochak.message.channel.provider.glific;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link GlificWhatsAppSettings} from this service's properties.
 *
 * <p>The expressions are the ones {@link GlificWhatsAppSender}'s {@code @Value} fields carried,
 * verbatim — including each purpose's dry-run fallback to {@code notifications.whatsapp.dry-run} — so
 * an environment resolves to exactly the settings it did before. What each value means is documented
 * on the settings record.</p>
 */
@Configuration
public class GlificWhatsAppConfig {

    @Value("${notifications.whatsapp.dry-run:false}")
    private boolean whatsappDryRun;

    @Value("${notifications.nudge.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean nudgeDryRun;

    @Value("${notifications.escalation.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean escalationDryRun;

    @Value("${notifications.daily-report.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean dailyReportDryRun;

    @Value("${notifications.weekly-report.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean weeklyReportDryRun;

    @Value("${glific.template.nudge-id:}")
    private String nudgeTemplateId;

    @Value("${glific.template.escalation-id:}")
    private String escalationTemplateId;

    @Value("${glific.template.login-otp-id:}")
    private String loginOtpTemplateId;

    @Value("${glific.template.daily-report-so-id:}")
    private String dailyReportSoTemplateId;

    @Value("${glific.template.daily-report-sdo-id:}")
    private String dailyReportSdoTemplateId;

    @Value("${glific.template.daily-report-so-link-id:}")
    private String dailyReportSoLinkTemplateId;

    @Value("${glific.template.daily-report-sdo-link-id:}")
    private String dailyReportSdoLinkTemplateId;

    @Value("${glific.template.weekly-report-so-link-id:}")
    private String weeklyReportSoLinkTemplateId;

    @Value("${glific.template.weekly-report-sdo-link-id:}")
    private String weeklyReportSdoLinkTemplateId;

    @Value("${glific.flow.nudge-id:}")
    private String nudgeFlowId;

    @Value("${glific.flow.welcome-id:}")
    private String welcomeFlowId;

    @Value("${minio.base-url:}")
    private String mediaBaseUrl;

    @Value("${glific.media.escalation-caption:Escalations}")
    private String escalationCaption;

    @Value("${glific.media.escalation-thumbnail:}")
    private String escalationThumbnail;

    @Value("${glific.media.daily-report-caption:Daily Water Service Situation Report}")
    private String dailyReportCaption;

    @Value("${notifications.daily-report.delivery-mode:DOCUMENT}")
    private String dailyReportDeliveryMode;

    @Value("${daily-report.link.button-base-url:}")
    private String dailyReportLinkButtonBaseUrl;

    @Bean
    public GlificWhatsAppSettings glificWhatsAppSettings() {
        return new GlificWhatsAppSettings(
                new GlificWhatsAppSettings.DryRun(
                        whatsappDryRun, nudgeDryRun, escalationDryRun, dailyReportDryRun, weeklyReportDryRun),
                new GlificWhatsAppSettings.Templates(
                        nudgeTemplateId,
                        escalationTemplateId,
                        loginOtpTemplateId,
                        dailyReportSoTemplateId,
                        dailyReportSdoTemplateId,
                        dailyReportSoLinkTemplateId,
                        dailyReportSdoLinkTemplateId,
                        weeklyReportSoLinkTemplateId,
                        weeklyReportSdoLinkTemplateId),
                new GlificWhatsAppSettings.Flows(nudgeFlowId, welcomeFlowId),
                new GlificWhatsAppSettings.Media(
                        mediaBaseUrl, escalationCaption, escalationThumbnail, dailyReportCaption),
                dailyReportDeliveryMode,
                dailyReportLinkButtonBaseUrl);
    }
}
