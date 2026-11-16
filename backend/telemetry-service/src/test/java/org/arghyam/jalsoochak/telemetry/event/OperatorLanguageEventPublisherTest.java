package org.arghyam.jalsoochak.telemetry.event;

import org.arghyam.jalsoochak.telemetry.dto.event.UserLanguageUpdateEvent;
import org.arghyam.jalsoochak.telemetry.kafka.KafkaProducer;
import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The operator's language change, handed to message-service as {@code UPDATE_USER_LANGUAGE} so it can
 * set the language on the operator's WhatsApp contact.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorLanguageEventPublisher")
class OperatorLanguageEventPublisherTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT = 17;
    private static final String PHONE = "919999900001";

    @Mock
    private KafkaProducer kafkaProducer;
    @Mock
    private LanguageCatalogRepository languageCatalogRepository;
    @InjectMocks
    private OperatorLanguageEventPublisher publisher;

    @Test
    void publishesTheCatalogIdForTheOperatorsPhoneKeyedByTheTenantCode() {
        when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(2));
        when(kafkaProducer.publishJson(anyString(), anyString(), any())).thenReturn(true);

        publisher.publishLanguageUpdated(SCHEMA, TENANT, PHONE, "Hindi");

        ArgumentCaptor<UserLanguageUpdateEvent> event = ArgumentCaptor.forClass(UserLanguageUpdateEvent.class);
        // The key matches user-service's, so both services' events for a tenant keep their order.
        verify(kafkaProducer).publishJson(eq("common-topic"), eq("AS"), event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("UPDATE_USER_LANGUAGE");
        assertThat(event.getValue().getTenantCode()).isEqualTo("AS");
        assertThat(event.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(event.getValue().getWhatsappLanguageId()).isEqualTo(2);
        assertThat(event.getValue().getPumpOperatorPhones()).isEqualTo(List.of(PHONE));
        assertThat(event.getValue().getTriggeredAt()).isNotBlank();
    }

    @Test
    void skipsALanguageTheCatalogDoesNotKnow() {
        when(languageCatalogRepository.findLanguageIdByAlias("Klingon")).thenReturn(Optional.empty());

        publisher.publishLanguageUpdated(SCHEMA, TENANT, PHONE, "Klingon");

        verifyNoInteractions(kafkaProducer);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void skipsAnOperatorWithoutAPhone(String phone) {
        publisher.publishLanguageUpdated(SCHEMA, TENANT, phone, "Hindi");

        verifyNoInteractions(kafkaProducer, languageCatalogRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"common_schema", "tenant_"})
    void skipsASchemaThatIsNotATenantSchema(String schema) {
        publisher.publishLanguageUpdated(schema, TENANT, PHONE, "Hindi");

        verifyNoInteractions(kafkaProducer, languageCatalogRepository);
    }

    @Test
    void aFailedPublishIsNotRaisedToTheCaller() {
        when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(2));
        when(kafkaProducer.publishJson(anyString(), anyString(), any())).thenReturn(false);

        assertThatCode(() -> publisher.publishLanguageUpdated(SCHEMA, TENANT, PHONE, "Hindi"))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsABlankLanguage() {
        publisher.publishLanguageUpdated(SCHEMA, TENANT, PHONE, " ");

        verify(kafkaProducer, never()).publishJson(anyString(), anyString(), any());
    }
}
