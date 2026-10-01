package org.arghyam.jalsoochak.message.channel.provider;

import java.time.LocalDate;

/**
 * Port interface for outbound WhatsApp delivery through a WhatsApp Business Solution Provider (BSP).
 *
 * <p>Everything here is BSP-generic: opting a phone number in, the provider's contact id and language
 * id, approved HSM templates and flows. Callers depend on this port only. Provider-specific concerns
 * (auth, the request and response shape, template and flow ids, dry-run flags) stay inside the
 * adapter.</p>
 *
 * <p>Unlike {@link SmsSender} and {@link EmailSender} there is no per-tenant factory: every tenant
 * shares one WhatsApp organisation, which is why {@code WHATSAPP} is absent from
 * {@code MessagingChannel}. The adapter is a single Spring bean.</p>
 *
 * <p>Every {@code contactId} is a boxed {@link Long} on purpose. The template and flow methods reject a
 * null or non-positive id with an {@link IllegalArgumentException}, which callers classify as a
 * configuration failure; a primitive {@code long} would turn a null into an unboxing
 * {@link NullPointerException} at the call site and misclassify it as a send failure.</p>
 *
 * <p>Methods block until the provider answers and throw on failure: a {@link WhatsAppSendException},
 * carrying the {@link WhatsAppSendStage} it failed at, when the provider rejects a call; an
 * {@link IllegalArgumentException} or {@link IllegalStateException} when our own configuration or
 * input is wrong. Anything else — a transport error or timeout — propagates as thrown. A send
 * suppressed by a dry-run flag returns normally without contacting the provider.</p>
 */
public interface WhatsAppSender {

    /**
     * Opts a phone number in to receive template messages.
     *
     * @param phoneNumber E.164 without the leading {@code +} (e.g. {@code 919876543210})
     * @return the provider's contact id for the number, or {@code 0} when none came back — including
     *         when a dry-run flag suppressed the opt-in
     */
    Long optIn(String phoneNumber);

    /**
     * Sets a contact's preferred language.
     *
     * @param contactId          the provider's contact id
     * @param providerLanguageId the provider's own id for the language
     */
    void updateContactLanguage(Long contactId, int providerLanguageId);

    /**
     * Starts the default welcome flow for a newly onboarded operator.
     *
     * @param name  operator display name, passed to the flow as {@code name}
     * @param state tenant state name, passed to the flow as {@code state}
     * @throws IllegalStateException if no default welcome flow is configured
     */
    void startWelcomeFlow(Long contactId, String name, String state);

    /**
     * Starts the given welcome flow — a tenant's override of the default one.
     *
     * @param flowId the provider's flow id
     * @throws IllegalStateException if {@code flowId} is blank
     */
    void startWelcomeFlow(Long contactId, String flowId, String name, String state);

    /**
     * Starts the interactive nudge flow, which sends a button template and continues on the
     * operator's reply.
     *
     * @param operatorName passed to the flow as {@code name}
     * @param date         today's date, passed to the flow as {@code date}
     */
    void startNudgeFlow(Long contactId, String operatorName, String date);

    /**
     * Sends the nudge template: {@code {{1}}} = operator name, {@code {{2}}} = today's date.
     */
    void sendNudgeHsm(Long contactId, String operatorName, String date);

    /**
     * Sends the login OTP template: {@code {{1}}} = OTP.
     */
    void sendLoginOtpHsm(Long contactId, String otp);

    /**
     * Sends the escalation PDF as a document template.
     *
     * @param documentUrl publicly reachable URL of the PDF. The provider's platform downloads it
     *                    itself, so an internal address is refused before any send
     */
    void sendEscalationHsm(Long contactId, String documentUrl);

    /**
     * Sends the Daily Water Service Situation Report, in the shape the configured
     * {@link ReportDeliveryMode} selects.
     *
     * @param documentUrl     publicly reachable URL of the report PDF
     * @param officerUserType SECTION_OFFICER | SUB_DIVISIONAL_OFFICER; picks the template
     * @param reportDate      the day the report's data covers (D-1)
     * @param officerName     the officer's name, a template variable in {@link ReportDeliveryMode#LINK}
     *                        mode
     * @return the accepted send's message id, template id and mode, or
     *         {@link WhatsAppSendResult#suppressed} when a dry-run flag suppressed it. <strong>Acceptance
     *         is not delivery</strong>: the provider reports the delivery status later
     */
    WhatsAppSendResult sendDailyReportHsm(Long contactId, String documentUrl, String officerUserType,
                                          LocalDate reportDate, String officerName);

    /**
     * Sends the Weekly Water Service Situation Report as a link-button template
     * ({@link ReportDeliveryMode#LINK} only).
     *
     * @param weekStart the first day of the reported week
     * @return as {@link #sendDailyReportHsm}
     */
    WhatsAppSendResult sendWeeklyReportHsm(Long contactId, String documentUrl, String officerUserType,
                                           LocalDate weekStart, String officerName);

    /**
     * Whether daily reports are actually delivered rather than suppressed. Lets a caller tell an
     * expected missing contact id (report muted) from a genuine failure.
     */
    boolean isDailyReportDeliveryEnabled();

    /** Whether weekly reports are actually delivered rather than suppressed. */
    boolean isWeeklyReportDeliveryEnabled();
}
