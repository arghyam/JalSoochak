package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.dto.TriggerWelcomeMessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WelcomeMessageTriggerService")
class WelcomeMessageTriggerServiceTest {

    private static final String PHONE = "919876543210";
    private static final int TENANT_ID = 7;
    private static final String STATE = "Madhya Pradesh";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MessageTemplateService messageTemplateService;

    @Mock
    private WhatsAppSender whatsAppSender;

    @Mock
    private PiiEncryptionService piiEncryptionService;

    @InjectMocks
    private WelcomeMessageTriggerService service;

    @BeforeEach
    void resolveTenant() {
        when(jdbcTemplate.query(contains("tenant_master_table"), any(RowMapper.class), eq("mp")))
                .thenReturn(List.of(TENANT_ID));
        when(messageTemplateService.findStateName(TENANT_ID)).thenReturn(STATE);
        when(piiEncryptionService.hmac(anyString())).thenReturn("phone-hash");
    }

    @Test
    @DisplayName("a stored contact id skips opt-in and starts the default flow when the tenant sets none")
    void storedContact_noTenantFlow_startsDefaultFlow() throws Exception {
        when(messageTemplateService.findWelcomeFlowId(TENANT_ID)).thenReturn(Optional.empty());
        stubStoredContact(55L, "enc-title", "Ramesh Kumar");

        TriggerWelcomeMessageResponse response = service.trigger("MP", PHONE);

        verify(whatsAppSender).startWelcomeFlow(55L, "Ramesh Kumar", STATE);
        verifyNoMoreInteractions(whatsAppSender);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getContactId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("a tenant welcome flow id is passed to the override")
    void storedContact_tenantFlow_startsThatFlow() throws Exception {
        when(messageTemplateService.findWelcomeFlowId(TENANT_ID)).thenReturn(Optional.of("flow-123"));
        stubStoredContact(55L, "enc-title", "Ramesh Kumar");

        service.trigger("mp", PHONE);

        verify(whatsAppSender).startWelcomeFlow(55L, "flow-123", "Ramesh Kumar", STATE);
        verifyNoMoreInteractions(whatsAppSender);
    }

    @Test
    @DisplayName("an unknown contact is opted in first, and the new contact id starts the flow")
    void noStoredContact_optsIn_thenStartsFlow() {
        when(messageTemplateService.findWelcomeFlowId(TENANT_ID)).thenReturn(Optional.empty());
        when(jdbcTemplate.query(contains("user_table"), any(RowMapper.class), anyString()))
                .thenReturn(List.of());
        when(whatsAppSender.optIn(PHONE)).thenReturn(77L);

        TriggerWelcomeMessageResponse response = service.trigger("mp", PHONE);

        verify(whatsAppSender).startWelcomeFlow(77L, null, STATE);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getContactId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("an opt-in that returns no contact id fails without starting any flow")
    void optInReturnsNoContact_failsWithoutFlow() {
        when(messageTemplateService.findWelcomeFlowId(TENANT_ID)).thenReturn(Optional.of("flow-123"));
        when(jdbcTemplate.query(contains("user_table"), any(RowMapper.class), anyString()))
                .thenReturn(List.of());
        when(whatsAppSender.optIn(PHONE)).thenReturn(0L);

        TriggerWelcomeMessageResponse response = service.trigger("mp", PHONE);

        verify(whatsAppSender).optIn(PHONE);
        verifyNoMoreInteractions(whatsAppSender);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getContactId()).isNull();
    }

    /** Answers the hashed-phone lookup with one row, run through the service's own row mapper. */
    @SuppressWarnings("unchecked")
    private void stubStoredContact(long contactId, String encryptedTitle, String title) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("whatsapp_connection_id", Long.class)).thenReturn(contactId);
        when(row.getString("title")).thenReturn(encryptedTitle);
        when(piiEncryptionService.safeDecrypt(encryptedTitle)).thenReturn(title);
        when(jdbcTemplate.query(contains("phone_number_hash"), any(RowMapper.class), eq("phone-hash")))
                .thenAnswer(inv -> List.of(inv.getArgument(1, RowMapper.class).mapRow(row, 0)));
    }
}
