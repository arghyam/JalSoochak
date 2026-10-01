package org.arghyam.jalsoochak.telemetry.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * How a failed chatbot answer is logged. Phone numbers are PII and may appear only at DEBUG; and an
 * operator typing letters where a number belongs is an expected outcome, not an ERROR with a stack
 * trace — on dev every such typo was paging-level noise that also carried the raw phone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MeterReadingConversationService — failure logging")
class MeterReadingConversationServiceFailureLoggingTest {

    private static final String PHONE = "919999912345";

    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ConversationLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private ConversationTemplateService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private LocationAffinityService locationAffinityService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MeterReadingConversationService service;

    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(MeterReadingConversationService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
        return appender.list;
    }

    private static List<ILoggingEvent> atInfoOrAbove(List<ILoggingEvent> events) {
        return events.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO)).toList();
    }

    @Test
    @DisplayName("an operator's invalid input is a WARN without a stack trace or the phone")
    void invalidInputIsAWarning() {
        List<ILoggingEvent> events = captureLogs(() -> service.manualReadingMessage(
                ManualReadingRequest.builder().contactId(PHONE).manualReading("abc").build()));

        assertThat(events).noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(atInfoOrAbove(events))
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(PHONE);
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    @Test
    @DisplayName("an unexpected failure stays an ERROR with its stack trace, with the phone masked")
    void unexpectedFailureIsAnError() {
        when(operatorContextService.resolveOperatorWithSchema(PHONE))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        List<ILoggingEvent> events = captureLogs(() -> service.manualReadingMessage(
                ManualReadingRequest.builder().contactId(PHONE).manualReading("1234").build()));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getFormattedMessage()).contains("****2345");
        });
        assertThat(atInfoOrAbove(events)).noneMatch(event -> event.getFormattedMessage().contains(PHONE));
    }
}
