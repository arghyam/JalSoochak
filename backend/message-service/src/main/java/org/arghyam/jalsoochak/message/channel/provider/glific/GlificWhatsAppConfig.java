package org.arghyam.jalsoochak.message.channel.provider.glific;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link GlificWhatsAppSettings} from this service's properties.
 *
 * <p>Each purpose's dry-run flag keeps the fallback to {@code notifications.whatsapp.dry-run} that
 * {@link GlificWhatsAppSender}'s former {@code @Value} fields carried. The provider values live under
 * {@code whatsapp.*}, bound from the {@code WHATSAPP_*} variables. What each value means is documented
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

    @Value("${whatsapp.template.nudge-id:}")
    private String nudgeTemplateId;

    @Value("${whatsapp.template.escalation-id:}")
    private String escalationTemplateId;

    @Value("${whatsapp.template.login-otp-id:}")
    private String loginOtpTemplateId;

    @Value("${whatsapp.template.daily-report-so-id:}")
    private String dailyReportSoTemplateId;

    @Value("${whatsapp.template.daily-report-sdo-id:}")
    private String dailyReportSdoTemplateId;

    @Value("${whatsapp.template.daily-report-so-link-id:}")
    private String dailyReportSoLinkTemplateId;

    @Value("${whatsapp.template.daily-report-sdo-link-id:}")
    private String dailyReportSdoLinkTemplateId;

    @Value("${whatsapp.template.weekly-report-so-link-id:}")
    private String weeklyReportSoLinkTemplateId;

    @Value("${whatsapp.template.weekly-report-sdo-link-id:}")
    private String weeklyReportSdoLinkTemplateId;

    @Value("${whatsapp.flow.nudge-id:}")
    private String nudgeFlowId;

    @Value("${whatsapp.flow.welcome-id:}")
    private String welcomeFlowId;

    @Value("${minio.base-url:}")
    private String mediaBaseUrl;

    @Value("${whatsapp.media.escalation-caption:Escalations}")
    private String escalationCaption;

    @Value("${whatsapp.media.escalation-thumbnail:}")
    private String escalationThumbnail;

    @Value("${whatsapp.media.daily-report-caption:Daily Water Service Situation Report}")
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
