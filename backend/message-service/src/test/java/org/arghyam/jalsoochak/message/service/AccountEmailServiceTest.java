package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.EmailSender;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountEmailService}.
 *
 * <p>Verifies that each public send-method delegates to {@link EmailSender} with
 * the correct {@link MailTemplate} and template variables, and that exceptions
 * thrown by the sender propagate to the caller (enabling Kafka DLT routing).</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountEmailServiceTest {

    @Mock
    private EmailSender mailSender;

    @InjectMocks
    private AccountEmailService accountEmailService;

    // ─────────────────────────── sendInviteEmail ───────────────────────────────

    @Test
    void sendInviteEmail_selectsStateAdminTemplate_forStateAdminRole() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendInviteEmail(
                "admin@state.gov", "Ravi Kumar", "STATE_ADMIN",
                "https://activate?token=abc", 24);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().template()).isEqualTo(MailTemplate.STATE_ADMIN_INVITATION);
    }

    @Test
    void sendInviteEmail_selectsSuperUserTemplate_forSuperUserRole() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendInviteEmail(
                "su@arghyam.in", "Priya", "SUPER_USER",
                "https://activate?token=xyz", 48);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().template()).isEqualTo(MailTemplate.SUPER_USER_INVITATION);
    }

    @Test
    void sendInviteEmail_selectsDefaultTemplate_forUnknownRole() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendInviteEmail(
                "op@tenant.in", "Mohan", "FIELD_OFFICER",
                "https://activate?token=def", 12);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().template()).isEqualTo(MailTemplate.DEFAULT_INVITATION);
    }

    @Test
    void sendInviteEmail_selectsDefaultTemplate_whenRoleIsNull() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendInviteEmail(
                "op@tenant.in", "Mohan", null,
                "https://activate?token=def", 12);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().template()).isEqualTo(MailTemplate.DEFAULT_INVITATION);
    }

    @Test
    void sendInviteEmail_populatesAllTemplateVariables() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendInviteEmail(
                "op@tenant.in", "Sunita", "SUPER_USER",
                "https://activate?token=tok1", 48);

        verify(mailSender).send(captor.capture());
        MailRequest req = captor.getValue();
        assertThat(req.to()).isEqualTo("op@tenant.in");
        assertThat(req.templateVariables()).containsEntry("name", "Sunita");
        assertThat(req.templateVariables()).containsEntry("activation_link", "https://activate?token=tok1");
        assertThat(req.templateVariables()).containsEntry("expiry_hours", 48);
    }

    @Test
    void sendInviteEmail_fallsBackToUser_whenNameIsNull() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendInviteEmail(
                "op@tenant.in", null, "STATE_ADMIN",
                "https://activate?token=def", 24);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().templateVariables()).containsEntry("name", "User");
    }

    @Test
    void sendInviteEmail_propagatesException_whenMailSenderThrows() {
        doThrow(new RuntimeException("delivery failed")).when(mailSender).send(any());

        assertThatThrownBy(() -> accountEmailService.sendInviteEmail(
                "op@tenant.in", "Dev", "STATE_ADMIN", "https://link", 24))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("delivery failed");
    }

    // ─────────────────────── sendStateAdminInviteEmail ─────────────────────────

    @Test
    void sendStateAdminInviteEmail_includesStateName_inTemplateVariables() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendStateAdminInviteEmail(
                "sa@mp.gov.in", "Nitish Kumar", "Madhya Pradesh",
                "https://activate?token=sa1", 24);

        verify(mailSender).send(captor.capture());
        MailRequest req = captor.getValue();
        assertThat(req.template()).isEqualTo(MailTemplate.STATE_ADMIN_INVITATION);
        assertThat(req.templateVariables()).containsEntry("state_name", "Madhya Pradesh");
        assertThat(req.templateVariables()).containsEntry("name", "Nitish Kumar");
        assertThat(req.templateVariables()).containsEntry("activation_link", "https://activate?token=sa1");
        assertThat(req.templateVariables()).containsEntry("expiry_hours", 24);
    }

    @Test
    void sendStateAdminInviteEmail_fallsBackEmptyString_whenStateNameIsNull() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendStateAdminInviteEmail(
                "sa@mp.gov.in", "Admin", null, "https://activate", 24);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().templateVariables()).containsEntry("state_name", "");
    }

    // ─────────────────────────── sendReinviteEmail ─────────────────────────────

    @Test
    void sendReinviteEmail_selectsReinvitationTemplate() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendReinviteEmail(
                "op@tenant.in", "Sunita", "https://activate?token=re1", 72);

        verify(mailSender).send(captor.capture());
        MailRequest req = captor.getValue();
        assertThat(req.template()).isEqualTo(MailTemplate.REINVITATION);
        assertThat(req.to()).isEqualTo("op@tenant.in");
        assertThat(req.templateVariables()).containsEntry("name", "Sunita");
        assertThat(req.templateVariables()).containsEntry("activation_link", "https://activate?token=re1");
        assertThat(req.templateVariables()).containsEntry("expiry_hours", 72);
    }

    @Test
    void sendReinviteEmail_fallsBackToUser_whenNameIsNull() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendReinviteEmail("op@tenant.in", null, "https://link", 48);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().templateVariables()).containsEntry("name", "User");
    }

    @Test
    void sendReinviteEmail_propagatesException_whenMailSenderThrows() {
        doThrow(new RuntimeException("delivery failed")).when(mailSender).send(any());

        assertThatThrownBy(() -> accountEmailService.sendReinviteEmail(
                "op@tenant.in", "Dev", "https://link", 24))
                .isInstanceOf(RuntimeException.class);
    }

    // ──────────────────────── sendPasswordResetEmail ───────────────────────────

    @Test
    void sendPasswordResetEmail_selectsPasswordResetTemplate() {
        ArgumentCaptor<MailRequest> captor = ArgumentCaptor.forClass(MailRequest.class);

        accountEmailService.sendPasswordResetEmail(
                "user@example.com", "https://reset?token=r1", 30);

        verify(mailSender).send(captor.capture());
        MailRequest req = captor.getValue();
        assertThat(req.template()).isEqualTo(MailTemplate.PASSWORD_RESET);
        assertThat(req.to()).isEqualTo("user@example.com");
        assertThat(req.templateVariables()).containsEntry("reset_link", "https://reset?token=r1");
        assertThat(req.templateVariables()).containsEntry("expiry_minutes", 30);
    }

    @Test
    void sendPasswordResetEmail_propagatesException_whenMailSenderThrows() {
        doThrow(new RuntimeException("delivery failed")).when(mailSender).send(any());

        assertThatThrownBy(() -> accountEmailService.sendPasswordResetEmail(
                "user@example.com", "https://link", 30))
                .isInstanceOf(RuntimeException.class);
    }
}
