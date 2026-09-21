package org.arghyam.jalsoochak.message.channel;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link EmailSender} implementation that delivers transactional emails via SMTP
 * using Spring's {@link JavaMailSender}.
 *
 * <p>Subject and body come from {@code notification.mail.smtp.templates.*} in
 * {@code application.yml}, which is this service's own configuration for every relay alike
 * (O2-17). Placeholder tokens in the form {@code {key}} are replaced with the corresponding
 * values from {@link MailRequest#templateVariables()}.
 *
 * <p>PER-TENANT-PROVIDERS: a plain class, not a {@code @Component}. One instance per relay —
 * {@code SystemDefaultProviders} pairs the platform's settings with Spring Boot's auto-configured
 * {@code spring.mail.*} sender, and {@link SmtpMailSenderFactory} pairs a tenant's settings with a
 * {@code JavaMailSenderImpl} built for that tenant's host and credentials — because several relays
 * now have to coexist in one process, which a singleton bean selected by
 * {@code @ConditionalOnProperty} could not do (O2-2). Everything below the constructor is
 * unchanged: same message, same interpolation, same outcomes.
 */
@Slf4j
public class SmtpMailSender implements EmailSender {

    private final SmtpSettings settings;
    private final JavaMailSender javaMailSender;

    /**
     * @param settings       the identity this instance sends under, and the platform's templates
     * @param javaMailSender the relay this instance sends through
     */
    public SmtpMailSender(SmtpSettings settings, JavaMailSender javaMailSender) {
        this.settings = settings;
        this.javaMailSender = javaMailSender;
    }

    @Override
    public void send(MailRequest request) {
        MailProperties.SmtpTemplate tmpl = resolveTemplate(request.template());

        // Merge logo_image from config so template authors can use {logo_image} in body templates.
        Map<String, Object> vars = new HashMap<>(request.templateVariables());
        vars.put("logo_image", settings.logoImageUrl() != null ? settings.logoImageUrl() : "");

        String subject = interpolate(tmpl.subject(), vars);
        String body = interpolate(tmpl.body(), vars);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.fromAddress());
        message.setTo(request.to());
        message.setSubject(subject);
        message.setText(body);

        try {
            javaMailSender.send(message);
            log.info("[SmtpMailSender] sent template={}", request.template());
        } catch (MailException e) {
            log.error("[SmtpMailSender] failure template={}: {}", request.template(), e.getMessage(), e);
            throw new RuntimeException("SmtpMailSender failure for " + request.template(), e);
        }
    }

    private String interpolate(String template, Map<String, Object> vars) {
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private MailProperties.SmtpTemplate resolveTemplate(MailTemplate template) {
        // Still checked here rather than in the constructor: a deployment whose templates are
        // missing failed at the first send before this class took them as a parameter, and moving
        // that to startup would be a behaviour change. The tenant path does not reach this — its
        // factory refuses to build a sender without templates, so the tenant falls back instead of
        // failing per message (O2-9).
        MailProperties.SmtpTemplates templates = settings.templates();
        if (templates == null) {
            throw new IllegalStateException(
                    "Missing SMTP configuration: notification.mail.smtp.templates must be configured when provider=smtp");
        }

        return switch (template) {
            case PASSWORD_RESET         -> templates.passwordReset();
            case REINVITATION           -> templates.reinvitation();
            case DEFAULT_INVITATION     -> templates.defaultInvitation();
            case SUPER_USER_INVITATION  -> templates.superUserInvitation();
            case STATE_ADMIN_INVITATION -> templates.stateAdminInvitation();
        };
    }
}
