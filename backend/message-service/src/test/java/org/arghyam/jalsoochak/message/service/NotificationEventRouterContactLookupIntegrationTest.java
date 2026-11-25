package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.channel.provider.glific.GlificAuthService;
import org.arghyam.jalsoochak.message.event.WhatsAppContactRegisteredEvent;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The router's operator lookup against a real {@code user_table}, whose {@code id} is {@code SERIAL}
 * (int4) as in the tenant schemas. A mocked {@link JdbcTemplate} never runs the row mapper, so only a
 * real database catches a column read as the wrong JDBC type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class NotificationEventRouterContactLookupIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Suppress GlificAuthService @PostConstruct login
    @MockBean
    private GlificAuthService glificAuthService;

    @MockBean
    private WhatsAppSender whatsAppSender;

    @MockBean
    private KafkaProducer kafkaProducer;

    @Autowired
    private NotificationEventRouter router;

    @Autowired
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String PHONE = "919876543210";
    private static final int LANGUAGE_ID = 2;

    @AfterEach
    void cleanUsers() {
        jdbcTemplate.update("DELETE FROM tenant_test.user_table");
    }

    @Test
    void updateUserLanguage_findsOperatorByPhoneHash() {
        insertOperator(PHONE, piiEncryptionService.hmac(PHONE), 555L);

        router.route(updateLanguageEvent());

        verify(whatsAppSender).updateContactLanguage(555L, LANGUAGE_ID);
    }

    @Test
    void updateUserLanguage_findsOperatorByPlainPhone_whenNoHashMatches() {
        insertOperator(PHONE, null, 556L);

        router.route(updateLanguageEvent());

        verify(whatsAppSender).updateContactLanguage(556L, LANGUAGE_ID);
    }

    @Test
    void updateUserLanguage_publishesOperatorIdWithNewContact_whenOperatorHasNoContactYet() {
        long userId = insertOperator(PHONE, piiEncryptionService.hmac(PHONE), null);
        when(whatsAppSender.optIn(anyString())).thenReturn(777L);

        router.route(updateLanguageEvent());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).publishJson(eq("common-topic"), event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(WhatsAppContactRegisteredEvent.class, e -> {
            assertThat(e.getUserId()).isEqualTo(userId);
            assertThat(e.getContactId()).isEqualTo(777L);
        });
        verify(whatsAppSender).updateContactLanguage(777L, LANGUAGE_ID);
    }

    private long insertOperator(String phone, String phoneHash, Long contactId) {
        Integer id = jdbcTemplate.queryForObject(
                "INSERT INTO tenant_test.user_table (title, phone_number, phone_number_hash, user_type,"
                        + " whatsapp_connection_id) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Integer.class, "Operator", phone, phoneHash, 1, contactId);
        return id.longValue();
    }

    private static String updateLanguageEvent() {
        return """
                {"eventType":"UPDATE_USER_LANGUAGE","tenantCode":"test",
                 "whatsappLanguageId":%d,"pumpOperatorPhones":["%s"]}
                """.formatted(LANGUAGE_ID, PHONE);
    }
}
