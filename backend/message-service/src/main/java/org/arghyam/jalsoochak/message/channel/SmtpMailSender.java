package org.arghyam.jalsoochak.message.channel;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link EmailSender} implementation that delivers transactional emails via SMTP
 * using Spring's {@link JavaMailSender}.
 *
 * <p>Subject and body are read from {@code notification.mail.smtp.templates.*} in
 * {@code application.yml}. Placeholder tokens in the form {@code {key}} are replaced
 * with the corresponding values from {@link MailRequest#templateVariables()}.
 *
 * <p>Activated when {@code notification.mail.provider=smtp}.
 * Requires {@code spring.mail.*} to be configured so that Spring Boot
 * auto-configures a {@link JavaMailSender} bean.
 */
@Component
@ConditionalOnProperty(name = "notification.mail.provider", havingValue = "smtp")
@Slf4j
public class SmtpMailSender implements EmailSender {

    private final MailProperties mailProperties;
    private final JavaMailSender javaMailSender;

    public SmtpMailSender(MailProperties mailProperties, JavaMailSender javaMailSender) {
        this.mailProperties = mailProperties;
        this.javaMailSender = javaMailSender;
    }

    @Override
    public void send(MailRequest request) {
        MailProperties.SmtpTemplate tmpl = resolveTemplate(request.template());

        // Merge logo_image from config so template authors can use {logo_image} in body templates.
        Map<String, Object> vars = new HashMap<>(request.templateVariables());
        vars.put("logo_image", mailProperties.logoImageUrl() != null ? mailProperties.logoImageUrl() : "");

        String subject = interpolate(tmpl.subject(), vars);
        String body = interpolate(tmpl.body(), vars);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.fromAddress());
        message.setTo(request.to());
        message.setSubject(subject);
        message.setText(body);

        Mono.fromCallable(() -> {
            try {
                javaMailSender.send(message);
                log.info("[SmtpMailSender] sent template={}", request.template());
            } catch (MailException e) {
                log.error("[SmtpMailSender] failure template={}: {}", request.template(), e.getMessage(), e);
                throw new RuntimeException("SmtpMailSender failure for " + request.template(), e);
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).block();
    }

    private String interpolate(String template, Map<String, Object> vars) {
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private MailProperties.SmtpTemplate resolveTemplate(MailTemplate template) {
        MailProperties.SmtpTemplates templates = mailProperties.smtp().templates();
        return switch (template) {
            case PASSWORD_RESET         -> templates.passwordReset();
            case REINVITATION           -> templates.reinvitation();
            case DEFAULT_INVITATION     -> templates.defaultInvitation();
            case SUPER_USER_INVITATION  -> templates.superUserInvitation();
            case STATE_ADMIN_INVITATION -> templates.stateAdminInvitation();
        };
    }
}
