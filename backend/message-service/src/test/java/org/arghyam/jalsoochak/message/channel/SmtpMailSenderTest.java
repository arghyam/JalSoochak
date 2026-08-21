package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.arghyam.jalsoochak.message.exception.PermanentMailException;
import org.arghyam.jalsoochak.message.exception.TransientMailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.MessagingException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmtpMailSenderTest {

    @Mock
    private JavaMailSender javaMailSender;

    private SmtpMailSender smtpMailSender;

    @BeforeEach
    void setUp() {
        MailProperties.SmtpTemplate pwReset = new MailProperties.SmtpTemplate(
                "Reset Your JalSoochak Password",
                "Dear User,\n\nReset: {reset_link}\n\nExpires in {expiry_minutes} minutes.");
        MailProperties.SmtpTemplate reinvite = new MailProperties.SmtpTemplate(
                "Reminder: Your JalSoochak Invitation",
                "Dear {name},\n\nActivate: {activation_link}\n\nExpires in {expiry_hours} hours.");
        MailProperties.SmtpTemplate defaultInvite = new MailProperties.SmtpTemplate(
                "You are invited to join JalSoochak",
                "Dear {name},\n\nActivate: {activation_link}\n\nExpires in {expiry_hours} hours.");
        MailProperties.SmtpTemplate superUser = new MailProperties.SmtpTemplate(
                "You are assigned as Super User by JalSoochak",
                "Dear {name},\n\nActivate: {activation_link}\n\nExpires in {expiry_hours} hours.");
        MailProperties.SmtpTemplate stateAdmin = new MailProperties.SmtpTemplate(
                "You are assigned as State System Admin by JalSoochak",
                "Dear {name},\n\nManage {state_name}.\n\nActivate: {activation_link}\n\nExpires in {expiry_hours} hours.");

        MailProperties.SmtpTemplates smtpTemplates = new MailProperties.SmtpTemplates(
                pwReset, reinvite, defaultInvite, superUser, stateAdmin);
        MailProperties mailProperties = new MailProperties(
                "smtp", "noreply@test.com", "Test", null,
                null,
                new MailProperties.Smtp(smtpTemplates));

        smtpMailSender = new SmtpMailSender(mailProperties, javaMailSender);
    }

    @Test
    void send_passwordReset_interpolatesResetLinkAndExpiry() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        smtpMailSender.send(new MailRequest(
                "user@example.com",
                MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset?t=abc", "expiry_minutes", 30)));

        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getSubject()).isEqualTo("Reset Your JalSoochak Password");
        assertThat(msg.getText()).contains("https://reset?t=abc");
        assertThat(msg.getText()).contains("30");
        assertThat(msg.getTo()).containsExactly("user@example.com");
        assertThat(msg.getFrom()).isEqualTo("noreply@test.com");
    }

    @Test
    void send_stateAdminInvitation_interpolatesStateName() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        smtpMailSender.send(new MailRequest(
                "admin@state.gov",
                MailTemplate.STATE_ADMIN_INVITATION,
                Map.of("name", "Ravi Kumar", "state_name", "Madhya Pradesh",
                        "activation_link", "https://activate?t=sa1", "expiry_hours", 24)));

        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getSubject()).isEqualTo("You are assigned as State System Admin by JalSoochak");
        assertThat(msg.getText()).contains("Ravi Kumar");
        assertThat(msg.getText()).contains("Madhya Pradesh");
        assertThat(msg.getText()).contains("https://activate?t=sa1");
    }

    @Test
    void send_defaultInvitation_interpolatesNameAndLink() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        smtpMailSender.send(new MailRequest(
                "op@tenant.in",
                MailTemplate.DEFAULT_INVITATION,
                Map.of("name", "Sunita", "activation_link", "https://activate?t=d1", "expiry_hours", 48)));

        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getSubject()).isEqualTo("You are invited to join JalSoochak");
        assertThat(msg.getText()).contains("Sunita");
        assertThat(msg.getText()).contains("https://activate?t=d1");
    }

    @Test
    void send_throwsTransient_whenTheConnectionIsNeverEstablished() {
        // Connection refused: no SMTP conversation happened at all, so no copy is in flight and a
        // replay is safe.
        doThrow(new MailSendException("Mail server connection failed",
                new MessagingException("connect failed", new ConnectException("Connection refused"))))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> smtpMailSender.send(new MailRequest(
                "user@example.com",
                MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset", "expiry_minutes", 15))))
                .isInstanceOf(TransientMailException.class)
                .hasMessageContaining("SmtpMailSender transport failure");
    }

    @Test
    void send_throwsPermanent_whenTheSendFailsAfterTheConnectionOpened() {
        // MailSendException also covers failures late in the conversation — a read timeout waiting
        // for the 250 after DATA, for instance. The server may already have accepted the message,
        // so retrying risks a second password-reset email. Ambiguous means permanent here.
        doThrow(new MailSendException("Failed message: read timed out after DATA",
                new MessagingException("Exception reading response",
                        new SocketTimeoutException("Read timed out"))))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> smtpMailSender.send(new MailRequest(
                "user@example.com",
                MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset", "expiry_minutes", 15))))
                .isInstanceOf(PermanentMailException.class)
                .hasMessageContaining("mid-conversation");
    }

    @Test
    void send_throwsPermanent_whenTheTransportFailureCarriesNoIdentifiableCause() {
        // Nothing in the cause chain proves the connection never opened, so delivery cannot be
        // ruled out and the event goes to the DLT rather than round the retry ladder.
        doThrow(new MailSendException("SMTP send failed"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> smtpMailSender.send(new MailRequest(
                "user@example.com",
                MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset", "expiry_minutes", 15))))
                .isInstanceOf(PermanentMailException.class);
    }

    @Test
    void send_throwsPermanent_whenCredentialsAreRejected() {
        // Bad credentials fail identically however many times the event is replayed.
        doThrow(new MailAuthenticationException("535 authentication failed"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> smtpMailSender.send(new MailRequest(
                "user@example.com",
                MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset", "expiry_minutes", 15))))
                .isInstanceOf(PermanentMailException.class)
                .hasMessageContaining("SmtpMailSender failure");
    }
}
