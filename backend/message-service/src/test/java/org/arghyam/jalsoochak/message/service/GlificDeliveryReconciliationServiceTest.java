package org.arghyam.jalsoochak.message.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.arghyam.jalsoochak.message.dto.GlificDeliveryOutcome;
import org.arghyam.jalsoochak.message.dto.GlificMessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlificDeliveryReconciliationService}.
 *
 * <p>The three goals this job exists for are asserted directly: delivered counts per role, failures
 * with their reason, and the officer user ids behind those failures. Alongside them sit the guards
 * that make those numbers trustworthy — the client-side template and direction filters, multi-tenant
 * fan-out, and the rule that no phone number ever reaches a log line.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlificDeliveryReconciliationServiceTest {

    private static final String SO = "SECTION_OFFICER";
    private static final String SDO = "SUB_DIVISIONAL_OFFICER";
    private static final int DAILY_REPORT_TEMPLATE = 880557;
    private static final int NUDGE_TEMPLATE = 770001;

    /** Not a real number. Present so a test can prove it never reaches a log line. */
    private static final String FIXTURE_PHONE = "919999900001";

    @Mock
    private GlificDeliveryStatusService glificDeliveryStatusService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private GlificDeliveryReconciliationService service;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    private final Instant from = Instant.parse("2026-08-27T00:30:00Z");
    private final Instant to = Instant.parse("2026-08-27T06:30:00Z");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "windowHours", 6L);
        ReflectionTestUtils.setField(service, "pageSize", 250);
        ReflectionTestUtils.setField(service, "maxPages", 40);
        ReflectionTestUtils.setField(service, "dateColumn", "inserted_at");
        ReflectionTestUtils.setField(service, "templateIdsCsv", "");
        ReflectionTestUtils.setField(service, "accountLevelErrorCodesCsv", "9999");
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", String.valueOf(DAILY_REPORT_TEMPLATE));
        ReflectionTestUtils.setField(service, "dailyReportSdoTemplateId", "");
        ReflectionTestUtils.setField(service, "dailyReportSoLinkTemplateId", "");
        ReflectionTestUtils.setField(service, "dailyReportSdoLinkTemplateId", "");

        logger = (Logger) LoggerFactory.getLogger(GlificDeliveryReconciliationService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    // ─────────────────────────────── kill switch ───────────────────────────────

    @Test
    void scheduledPassIsANoOpWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.reconcileScheduled();

        verifyNoInteractions(glificDeliveryStatusService, jdbcTemplate);
    }

    /**
     * With no template ids there is nothing to match, so every message in the window would be
     * discarded. Better to refuse the pass loudly than to log a confident-looking zero.
     */
    @Test
    void refusesToRunWithNoConfiguredTemplateIds() {
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "");

        service.reconcile(from, to);

        verifyNoInteractions(glificDeliveryStatusService);
        assertThat(logLines()).anyMatch(l -> l.contains("No daily-report template ids configured"));
    }

    // ───────────────────────── goals 1, 2 and 3 ────────────────────────────────

    @Nested
    class DeliveryOutcomes {

        @Test
        void countsDeliveriesPerRole() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6530736L, officer(16714L, SO), 6530737L, officer(16702L, SDO)));
            stubStatus("DELIVERED",
                    delivered("241952654", 6530736L),
                    delivered("241952655", 6530737L));

            service.reconcile(from, to);

            assertThat(summaryTotal())
                    .contains("deliveredByRole={SECTION_OFFICER=1, SUB_DIVISIONAL_OFFICER=1}")
                    .contains("matched=2");
        }

        /** READ implies delivered and is counted separately rather than lost. */
        @Test
        void countsReadSeparatelyFromDelivered() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6530736L, officer(16714L, SO)));
            stubStatus("READ", message("1", "READ", 6530736L, GlificDeliveryOutcome.READ, null, null));

            service.reconcile(from, to);

            assertThat(summaryTotal()).contains("readByRole={SECTION_OFFICER=1}");
        }

        @Test
        void countsFailuresByRoleAndByCode() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6629592L, officer(16733L, SO), 6629593L, officer(16755L, SO)));
            stubStatus("ERROR", undeliverable("241952232", 6629592L));
            stubStatus("CONTACT_OPT_OUT", optedOut("241952240", 6629593L));

            service.reconcile(from, to);

            assertThat(summaryTotal())
                    .contains("failedByRole={SECTION_OFFICER=2}")
                    .contains("failedByCode={131026=1, CONTACT_OPT_OUT=1}");
        }

        /** Goal 3: the officer ids behind each failure, grouped by the code that caused it. */
        @Test
        void listsTheFailedOfficerIdsGroupedByErrorCode() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6629592L, officer(16733L, SO), 6629593L, officer(16744L, SO)));
            stubStatus("ERROR",
                    undeliverable("1", 6629592L),
                    undeliverable("2", 6629593L));

            service.reconcile(from, to);

            assertThat(logLines()).anyMatch(l -> l.contains("failedOfficers:")
                    && l.contains("role=SECTION_OFFICER")
                    && l.contains("errorCode=131026")
                    && l.contains("count=2")
                    && l.contains("16733")
                    && l.contains("16744"));
        }

        @Test
        void emitsNoFailedOfficersLineWhenNothingFailed() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6530736L, officer(16714L, SO)));
            stubStatus("DELIVERED", delivered("1", 6530736L));

            service.reconcile(from, to);

            assertThat(logLines()).noneMatch(l -> l.contains("failedOfficers:"));
        }

        /** Glific's SENT is "Meta has it, not delivered" — it must land in pending, never in delivered. */
        @Test
        void glificSentCountsAsPendingNotDelivered() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6530736L, officer(16714L, SO)));
            stubStatus("SENT", message("1", "SENT", 6530736L, GlificDeliveryOutcome.PENDING, null, null));

            service.reconcile(from, to);

            assertThat(summaryTotal()).contains("pending=1").contains("deliveredByRole={}");
        }
    }

    // ────────────────────────── client-side filtering ──────────────────────────

    @Nested
    class Filtering {

        @Test
        void discardsMessagesOnOtherTemplates() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6530736L, officer(16714L, SO)));
            stubStatus("DELIVERED",
                    delivered("1", 6530736L),
                    withTemplate(delivered("2", 6530736L), NUDGE_TEMPLATE));

            service.reconcile(from, to);

            assertThat(summaryTotal()).contains("matched=1").contains("discardedOtherTemplates=1");
        }

        /**
         * An inbound message's receiver is our own org contact, not an officer. Counting one would
         * attribute a delivery status to entirely the wrong person.
         */
        @Test
        void discardsInboundMessages() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6530736L, officer(16714L, SO)));
            stubStatus("DELIVERED",
                    delivered("1", 6530736L),
                    new GlificMessageStatus("2", "wamid.x", "DELIVERED", null, false, "INBOUND",
                            2239259L, GlificDeliveryOutcome.DELIVERED, null, null));

            service.reconcile(from, to);

            assertThat(summaryTotal()).contains("matched=1").contains("discardedInbound=1");
        }

        @Test
        void skipsAStatusGlificReportsAsEmpty() {
            stubTenants(tenant(74, "MH"));
            when(glificDeliveryStatusService.countMessages(any(), any(), anyString(), anyString()))
                    .thenReturn(0);

            service.reconcile(from, to);

            verify(glificDeliveryStatusService, never())
                    .fetchMessages(any(), any(), anyString(), anyString(), anyInt(), anyInt());
        }

        /**
         * A quiet window still reports, in the same line shape as a busy one. A silent pass is
         * indistinguishable from a job that has stopped running, and a different shape would break
         * whatever parses these lines.
         */
        @Test
        void aWindowWithNothingOfOursStillLogsASummaryTotal() {
            stubTenants(tenant(74, "MH"));
            when(glificDeliveryStatusService.countMessages(any(), any(), anyString(), anyString()))
                    .thenReturn(0);

            service.reconcile(from, to);

            assertThat(summaryTotal())
                    .contains("tenants=0")
                    .contains("matched=0")
                    .contains("deliveredByRole={}")
                    .contains("failedByCode={}")
                    .contains("unmappedContacts=0");
        }

        @Test
        void derivesTemplateIdsFromBothDeliveryModes() {
            ReflectionTestUtils.setField(service, "dailyReportSdoTemplateId", "880558");
            ReflectionTestUtils.setField(service, "dailyReportSoLinkTemplateId", "880559");

            assertThat(service.resolveTemplateIds()).containsExactlyInAnyOrder(880557, 880558, 880559);
        }

        @Test
        void anExplicitTemplateIdListOverridesTheDerivedOne() {
            ReflectionTestUtils.setField(service, "templateIdsCsv", " 111 , 222 ");

            assertThat(service.resolveTemplateIds()).containsExactlyInAnyOrder(111, 222);
        }

        @Test
        void ignoresANonNumericTemplateId() {
            ReflectionTestUtils.setField(service, "templateIdsCsv", "111,not-a-number");

            assertThat(service.resolveTemplateIds()).containsExactly(111);
        }
    }

    // ──────────────────────────── multi-tenant ─────────────────────────────────

    @Nested
    class MultiTenant {

        @Test
        void emitsOneSummaryPerTenantPlusATotal() {
            stubTenants(tenant(74, "MH"), tenant(81, "UP"));
            stubOfficersPerTenant(Map.of(
                    "tenant_mh", Map.of(6530736L, officer(16714L, SO)),
                    "tenant_up", Map.of(6530800L, officer(20001L, SDO))));
            stubStatus("DELIVERED", delivered("1", 6530736L), delivered("2", 6530800L));

            service.reconcile(from, to);

            assertThat(logLines()).anyMatch(l -> l.contains("summary:") && l.contains("tenant=74"));
            assertThat(logLines()).anyMatch(l -> l.contains("summary:") && l.contains("tenant=81"));
            assertThat(summaryTotal()).contains("tenants=2").contains("matched=2")
                    .contains("deliveredByRole={SECTION_OFFICER=1, SUB_DIVISIONAL_OFFICER=1}");
        }

        /** A recipient nobody claims is a stale whatsapp_connection_id — surfaced, not swallowed. */
        @Test
        void reportsContactsThatMatchNoOfficer() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of());
            stubStatus("DELIVERED", delivered("1", 6530736L));

            service.reconcile(from, to);

            assertThat(summaryTotal()).contains("unmappedContacts=1").contains("matched=0");
            assertThat(logLines()).anyMatch(l -> l.contains("result=UNMAPPED_CONTACT"));
        }

        /** One tenant's schema being absent mid-migration must not abort the whole pass. */
        @Test
        void survivesAFailingTenantQuery() {
            stubTenants(tenant(74, "MH"), tenant(81, "UP"));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenAnswer(inv -> {
                        if (inv.getArgument(0).toString().contains("tenant_mh")) {
                            throw new IllegalStateException("relation does not exist");
                        }
                        return rowsFor(inv.getArgument(1), Map.of(6530800L, officer(20001L, SDO)));
                    });
            stubStatus("DELIVERED", delivered("1", 6530800L));

            service.reconcile(from, to);

            assertThat(logLines()).anyMatch(l -> l.contains("Could not resolve officers in tenant=74"));
            assertThat(summaryTotal()).contains("matched=1");
        }
    }

    // ───────────────────── account-level failures + privacy ────────────────────

    @Nested
    class AccountLevelAndPrivacy {

        /**
         * A zero Gupshup balance fails every message in flight. Reported on its own line, because
         * counting it as N officer failures reads as a mass recipient-data problem it is not.
         */
        @Test
        void reportsAnAccountLevelFailureSeparately() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6275488L, officer(16733L, SO)));
            stubStatus("ERROR", lowBalance("233212612", 6275488L));

            service.reconcile(from, to);

            assertThat(logLines()).anyMatch(l -> l.contains("ACCOUNT-LEVEL FAILURE")
                    && l.contains("errorCode=9999")
                    && l.contains("Gupshup account condition"));
        }

        /**
         * A low-balance rejection can arrive with a null templateId — the real sample did — so it would
         * be filtered out before ever reaching the tally. It is counted over the unfiltered set for
         * exactly that reason.
         */
        @Test
        void catchesAnAccountLevelFailureEvenWhenItHasNoTemplateId() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of());
            stubStatus("ERROR", withTemplate(lowBalance("1", 6275488L), null));

            service.reconcile(from, to);

            assertThat(logLines()).anyMatch(l -> l.contains("ACCOUNT-LEVEL FAILURE"));
            assertThat(summaryTotal()).contains("matched=0");
        }

        /** The privacy rule, asserted over every line the pass emits at any level. */
        @Test
        void neverLogsAPhoneNumber() {
            stubTenants(tenant(74, "MH"));
            stubOfficers(Map.of(6629592L, officer(16733L, SO)));
            stubStatus("ERROR", new GlificMessageStatus("1", "gs-1", "ERROR", DAILY_REPORT_TEMPLATE, true,
                    "OUTBOUND", 6629592L, GlificDeliveryOutcome.DELIVERY_FAILED, "131026",
                    "Message undeliverable to " + FIXTURE_PHONE));

            service.reconcile(from, to);

            // The service must not introduce a number of its own; a reason arriving with one already
            // redacted upstream is the status service's contract, tested there.
            assertThat(logLines()).noneMatch(l -> l.matches(".*\\b91\\d{10}\\b.*"));
        }
    }

    // ─────────────────────────────── helpers ───────────────────────────────────

    private record TenantFixture(int id, String stateCode) {}

    private static TenantFixture tenant(int id, String stateCode) {
        return new TenantFixture(id, stateCode);
    }

    private record OfficerFixture(long userId, String role) {}

    private static OfficerFixture officer(long userId, String role) {
        return new OfficerFixture(userId, role);
    }

    private void stubTenants(TenantFixture... tenants) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(inv -> {
            RowMapper<Object> rm = inv.getArgument(1);
            List<Object> rows = new ArrayList<>();
            for (TenantFixture t : tenants) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getInt("id")).thenReturn(t.id());
                when(rs.getString("state_code")).thenReturn(t.stateCode());
                rows.add(rm.mapRow(rs, 0));
            }
            return rows;
        });
    }

    /** Every tenant resolves the same officer map — enough for the single-tenant cases. */
    private void stubOfficers(Map<Long, OfficerFixture> byContactId) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> rowsFor(inv.getArgument(1), byContactId));
    }

    /** Each tenant schema resolves only its own officers — for the multi-tenant cases. */
    private void stubOfficersPerTenant(Map<String, Map<Long, OfficerFixture>> bySchema) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    Map<Long, OfficerFixture> match = bySchema.entrySet().stream()
                            .filter(e -> sql.contains(e.getKey()))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(Map.of());
                    return rowsFor(inv.getArgument(1), match);
                });
    }

    private static List<Object> rowsFor(RowMapper<Object> rm, Map<Long, OfficerFixture> byContactId)
            throws Exception {
        List<Object> rows = new ArrayList<>();
        for (Map.Entry<Long, OfficerFixture> e : byContactId.entrySet()) {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("whatsapp_connection_id")).thenReturn(e.getKey());
            when(rs.getLong("id")).thenReturn(e.getValue().userId());
            when(rs.getString("user_type")).thenReturn(e.getValue().role());
            rows.add(rm.mapRow(rs, 0));
        }
        return rows;
    }

    private void stubStatus(String bspStatus, GlificMessageStatus... messages) {
        when(glificDeliveryStatusService.countMessages(any(), any(), eq(bspStatus), anyString()))
                .thenReturn(messages.length);
        when(glificDeliveryStatusService.fetchMessages(any(), any(), eq(bspStatus), anyString(),
                anyInt(), anyInt())).thenReturn(List.of(messages));
    }

    private static GlificMessageStatus delivered(String id, long contactId) {
        return message(id, "DELIVERED", contactId, GlificDeliveryOutcome.DELIVERED, null, null);
    }

    private static GlificMessageStatus undeliverable(String id, long contactId) {
        return message(id, "ERROR", contactId, GlificDeliveryOutcome.DELIVERY_FAILED, "131026",
                "Message undeliverable, (#131026) Message Undeliverable.");
    }

    private static GlificMessageStatus optedOut(String id, long contactId) {
        return message(id, "CONTACT_OPT_OUT", contactId, GlificDeliveryOutcome.DELIVERY_FAILED,
                "CONTACT_OPT_OUT", "contact opted out");
    }

    private static GlificMessageStatus lowBalance(String id, long contactId) {
        return message(id, "ERROR", contactId, GlificDeliveryOutcome.DELIVERY_FAILED, "9999", "low balance");
    }

    private static GlificMessageStatus message(String id, String bspStatus, long contactId,
                                               GlificDeliveryOutcome outcome, String code, String reason) {
        return new GlificMessageStatus(id, "gs-" + id, bspStatus, DAILY_REPORT_TEMPLATE, true, "OUTBOUND",
                contactId, outcome, code, reason);
    }

    private static GlificMessageStatus withTemplate(GlificMessageStatus m, Integer templateId) {
        return new GlificMessageStatus(m.messageId(), m.bspMessageId(), m.bspStatus(), templateId, m.hsm(),
                m.flow(), m.receiverContactId(), m.outcome(), m.errorCode(), m.errorReason());
    }

    private List<String> logLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String summaryTotal() {
        return logLines().stream()
                .filter(l -> l.contains("summaryTotal:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summaryTotal line was logged; lines were "
                        + logLines()));
    }
}
