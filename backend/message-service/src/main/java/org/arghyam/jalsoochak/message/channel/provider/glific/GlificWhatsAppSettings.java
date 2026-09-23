package org.arghyam.jalsoochak.message.channel.provider.glific;

import java.util.Objects;

/**
 * Everything one {@link GlificWhatsAppSender} needs beyond its GraphQL client, assembled for it by
 * whoever builds it — today {@link GlificWhatsAppConfig}, from this service's properties.
 *
 * <p>A construction parameter rather than {@code @Value} fields on the sender, so that a per-tenant
 * factory could later build one sender per WhatsApp organisation without touching the send path.
 * There is no such factory: every tenant shares one organisation (see {@code WhatsAppSender}).</p>
 *
 * <p>Carries no credential — the login stays with {@link GlificAuthService} — so the generated
 * {@code toString} is safe to log. That service and {@link GlificGraphQLClient} read only
 * {@link #dryRun()}, to decide whether blank connection settings may be tolerated at startup.</p>
 *
 * @param dryRun                  which purposes are suppressed
 * @param templates               the approved HSM template ids
 * @param flows                   the flow ids
 * @param media                   the public media prefix and the document captions
 * @param dailyReportDeliveryMode {@code notifications.daily-report.delivery-mode}, unparsed — see
 *                                {@link org.arghyam.jalsoochak.message.channel.provider.ReportDeliveryMode}.
 *                                Parsed on use so an unrecognised value fails loudly with the valid
 *                                ones named, and so an unset property behaves exactly as before this
 *                                mode existed
 * @param linkButtonBaseUrl       optional mirror of the URL prefix frozen into the approved LINK
 *                                templates, e.g. {@code https://jalsoochak.jjmbrain.in/minio/}. When
 *                                set it must match the prefix the sender strips off the media URL to
 *                                build the button's variable, and a mismatch fails startup. It is the
 *                                only check that catches an environment deployed with another
 *                                environment's template id or base URL — the send still succeeds in
 *                                that case, and the officer is the one who discovers the button leads
 *                                nowhere
 */
public record GlificWhatsAppSettings(
        DryRun dryRun,
        Templates templates,
        Flows flows,
        Media media,
        String dailyReportDeliveryMode,
        String linkButtonBaseUrl) {

    public GlificWhatsAppSettings {
        Objects.requireNonNull(dryRun, "dryRun");
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(media, "media");
    }

    /**
     * Which WhatsApp purposes are suppressed. Every purpose flag defaults to {@link #whatsapp()} when
     * its own property is unset, so a single {@code NOTIFICATIONS_WHATSAPP_DRY_RUN=true} still
     * suppresses every Glific call.
     *
     * @param whatsapp     the master flag. Gates the shared account operations that are neither a
     *                     nudge, an escalation nor a report: login OTP, welcome flow and language
     *                     updates. Contact opt-in is deliberately <em>not</em> gated on it: opt-in
     *                     sends the recipient nothing and is the prerequisite for every delivery, so
     *                     muting account operations must not break a purpose that is switched live
     * @param nudge        the operator nudge, flow and HSM. {@code NOTIFICATIONS_NUDGE_DRY_RUN=true}
     *                     mutes nudges while escalations stay live
     * @param escalation   the officer escalation document HSM and its media upload.
     *                     {@code NOTIFICATIONS_ESCALATION_DRY_RUN=false} delivers escalations to
     *                     officers (SO/SDO) while nudges stay muted
     * @param dailyReport  the Daily Water Service Situation Report and its media upload.
     *                     {@code NOTIFICATIONS_DAILY_REPORT_DRY_RUN=false} delivers officer daily
     *                     reports while nudges, escalations and account operations stay muted
     * @param weeklyReport the Weekly Water Service Situation Report. Ships suppressed in practice,
     *                     because the weekly templates need their own Meta approval and until they
     *                     exist there is nothing to send. A suppressed weekly report is still generated
     *                     and uploaded, so the pipeline can be verified end-to-end before delivery
     *                     goes live
     */
    public record DryRun(boolean whatsapp, boolean nudge, boolean escalation, boolean dailyReport,
                         boolean weeklyReport) {

        /** Whether every purpose is suppressed — the only configuration that makes no Glific call at all. */
        public boolean allPurposes() {
            return whatsapp && nudge && escalation && dailyReport && weeklyReport;
        }
    }

    /**
     * The approved HSM template ids. The document templates ({@code dailyReportSo},
     * {@code dailyReportSdo}, {@code escalation}) are sent as integers and must be numeric; the text
     * templates travel as a GraphQL {@code ID!}. Each SDO id is optional and falls back to its SO
     * counterpart, so a deployment that has approved only one template still serves both roles.
     *
     * @param dailyReportSo      document template, SECTION_OFFICER daily report
     * @param dailyReportSdo     document template, SUB_DIVISIONAL_OFFICER daily report
     * @param dailyReportSoLink  text template with a dynamic-URL button, SECTION_OFFICER daily report
     * @param dailyReportSdoLink text template with a dynamic-URL button, SUB_DIVISIONAL_OFFICER daily
     *                           report
     * @param weeklyReportSoLink text template with a dynamic-URL button, SECTION_OFFICER weekly report
     * @param weeklyReportSdoLink text template with a dynamic-URL button, SUB_DIVISIONAL_OFFICER weekly
     *                            report
     */
    public record Templates(
            String nudge,
            String escalation,
            String loginOtp,
            String dailyReportSo,
            String dailyReportSdo,
            String dailyReportSoLink,
            String dailyReportSdoLink,
            String weeklyReportSoLink,
            String weeklyReportSdoLink) {
    }

    /**
     * @param nudge   the interactive nudge flow, which sends a button template and continues on the
     *                operator's reply
     * @param welcome the default welcome flow for a newly onboarded operator; a tenant may override it
     */
    public record Flows(String nudge, String welcome) {
    }

    /**
     * @param baseUrl             the prefix of every media URL handed to Glific ({@code minio.base-url}).
     *                            Read by the sender — not only by the storage service — because the
     *                            sender owns the Glific contract and is the only class that knows
     *                            whether a document-sending purpose is live. Meta downloads the URL from
     *                            the public internet, so an internal address must stop the service from
     *                            starting rather than reach officers as an unopenable attachment
     * @param escalationCaption   the escalation document's caption
     * @param escalationThumbnail the thumbnail registered with every document
     * @param dailyReportCaption  the daily report's document name, before the report date is appended
     */
    public record Media(String baseUrl, String escalationCaption, String escalationThumbnail,
                        String dailyReportCaption) {
    }
}
