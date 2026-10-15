package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.statesync.StateSyncIntegrationTestBase;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncIssue;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeReconciler} with {@link UserDirectory} and {@link UserSchemeMappings} against real
 * PostgreSQL. Phone numbers are fictitious {@code 91XXXXXXXXXX}-shaped test values.
 */
class SchemeReconcilerIntegrationTest extends StateSyncIntegrationTestBase {

    private static final String SO_PHONE = "9100000001";
    private static final String PO_PHONE = "9100000002";

    private StateSyncTenantRepository repository;
    private int village1;
    private int village2;
    private int subdivision;

    private final UpstreamPerson so = person("USR-016363", "Thagen Saikia", SO_PHONE, "section-officer");
    private final UpstreamPerson po = person("USR-021920", "Bolin Gogoi", PO_PHONE, "jal-mitra");

    @BeforeEach
    void setUp() {
        repository = new StateSyncTenantRepository(jdbc);
        int district = lgd("Dibrugarh", 2, stateLgd, "DST-061");
        int block = lgd("Tingkhong", 3, district, "BLK-0089");
        int panchayat = lgd("Dillibari", 4, block, "PAN-00994");
        village1 = lgd("Chapatali No.2", 5, panchayat, "VIL-008633");
        village2 = lgd("Chapatali No.4", 5, panchayat, "VIL-008635");
        int division = dept("Dibrugarh", 4, stateDept, "DIV-020");
        subdivision = dept("Naharkatia", 5, division, "SDV-035");
    }

    private SyncReport sync(UpstreamScheme... schemes) {
        SyncReport report = new SyncReport();
        reconciler().reconcile(List.of(schemes), report);
        return report;
    }

    private SyncReport syncAllowingDowngrade(boolean allow, UpstreamScheme... schemes) {
        SyncReport report = new SyncReport();
        UserDirectory users = new UserDirectory(repository, pii, SCHEMA, TENANT_ID, actor);
        users.allowRoleDowngrades(allow);
        UserSchemeMappings mappings = new UserSchemeMappings(repository, SCHEMA, actor);
        new SchemeReconciler(repository, properties, SCHEMA, actor, users, mappings).reconcile(List.of(schemes), report);
        return report;
    }

    private SyncReport syncWithBlocked(Set<String> blocked, UpstreamScheme... schemes) {
        SyncReport report = new SyncReport();
        UserDirectory users = new UserDirectory(repository, pii, SCHEMA, TENANT_ID, actor);
        UserSchemeMappings mappings = new UserSchemeMappings(repository, SCHEMA, actor);
        // As the runner does, before the schemes.
        users.useBlockedList(blocked);
        blocked.forEach(code -> users.block(code, report).ifPresent(u -> mappings.retireAllForUser(u.id(), report)));
        new SchemeReconciler(repository, properties, SCHEMA, actor, users, mappings).reconcile(List.of(schemes), report);
        return report;
    }

    private String roleOf(String code) {
        return jdbc.queryForObject("SELECT ut.c_name FROM tenant_as.user_table u JOIN common_schema.user_type_master_table ut "
                + "ON ut.id = u.user_type WHERE u.state_user_id = ?", String.class, code);
    }

    private int userStatus(String code) {
        return jdbc.queryForObject("SELECT status FROM tenant_as.user_table WHERE state_user_id = ?", Integer.class, code);
    }

    private SchemeReconciler reconciler() {
        UserDirectory users = new UserDirectory(repository, pii, SCHEMA, TENANT_ID, actor);
        UserSchemeMappings mappings = new UserSchemeMappings(repository, SCHEMA, actor);
        return new SchemeReconciler(repository, properties, SCHEMA, actor, users, mappings);
    }

    private UpstreamScheme chapatoli(List<String> villages, List<UpstreamPerson> officers) {
        return upstream("SCH-000008", "8165607", "34123", "CHAPATOLI PWSS", List.of("SDV-035"), villages, officers);
    }

    private int schemeId(String code) {
        return jdbc.queryForObject("SELECT id FROM tenant_as.scheme_master_table WHERE state_scheme_code = ?", Integer.class, code);
    }

    private int liveOfficers(int schemeId) {
        return count("SELECT COUNT(*) FROM tenant_as.user_scheme_mapping_table WHERE scheme_id = ? AND status = 1 "
                + "AND deleted_at IS NULL", schemeId);
    }

    @Test
    void createsANewSchemeWithItsLocationsAndOfficers() {
        SyncReport report = sync(chapatoli(List.of("VIL-008633", "VIL-008635"), List.of(so, po)));

        int id = schemeId("SCH-000008");
        assertThat(jdbc.queryForMap("SELECT state_scheme_id, centre_scheme_id, scheme_name, work_status, "
                + "operating_status, planned_fhtc, fhtc_count FROM tenant_as.scheme_master_table WHERE id = ?", id))
                .containsEntry("state_scheme_id", "34123").containsEntry("centre_scheme_id", "8165607")
                .containsEntry("work_status", 1).containsEntry("operating_status", 1)
                .containsEntry("planned_fhtc", 100).containsEntry("fhtc_count", 80);
        assertThat(jdbc.queryForList("SELECT parent_lgd_id FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? "
                + "AND deleted_at IS NULL", Integer.class, id)).containsExactlyInAnyOrder(village1, village2);
        assertThat(jdbc.queryForList("SELECT parent_department_id FROM tenant_as.scheme_department_mapping_table "
                + "WHERE scheme_id = ? AND deleted_at IS NULL", Integer.class, id)).containsExactly(subdivision);
        assertThat(liveOfficers(id)).isEqualTo(2);

        // Users are created encrypted, hashed on the 91-prefixed phone, and carry their upstream code.
        Map<String, Object> created = jdbc.queryForMap("SELECT title, phone_number, phone_number_hash, state_user_id, "
                + "email, status FROM tenant_as.user_table WHERE state_user_id = 'USR-021920'");
        assertThat(pii.decrypt((String) created.get("phone_number"))).isEqualTo("91" + PO_PHONE);
        assertThat(created.get("phone_number_hash")).isEqualTo(pii.hmac("91" + PO_PHONE));
        assertThat(pii.decrypt((String) created.get("title"))).isEqualTo("Bolin Gogoi");
        assertThat(created.get("email")).isNull();
        assertThat(report.get("schemes.inserted")).isEqualTo(1);
        assertThat(report.get("users.inserted")).isEqualTo(2);
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void aSecondIdenticalRunWritesNothing() {
        UpstreamScheme scheme = chapatoli(List.of("VIL-008633"), List.of(so, po));
        sync(scheme);
        String before = snapshot();

        SyncReport second = sync(scheme);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(second.get("schemes.unchanged")).isEqualTo(1);
        assertThat(second.counts().keySet()).noneMatch(k -> k.endsWith(".inserted") || k.endsWith(".retired")
                || k.endsWith(".revived") || k.equals("schemes.updated") || k.startsWith("users.name")
                || k.startsWith("users.role") || k.equals("users.code_stamped"));
    }

    @Test
    void matchesALegacySchemeByItsIdPairAndKeepsWhatUpstreamLeavesBlank() {
        int legacy = scheme("34123", "8165607", "Chapatoli", null);
        jdbc.update("UPDATE tenant_as.scheme_master_table SET latitude = 26.5, work_status = 2 WHERE id = ?", legacy);
        UpstreamScheme up = new UpstreamScheme("SCH-000008", "8165607", "34123", "CHAPATOLI PWSS", "abandoned",
                "operative", 100, 80, null, "", List.of(), List.of(), List.of(), LocalDateTime.now());

        SyncReport report = sync(up);

        assertThat(jdbc.queryForMap("SELECT state_scheme_code, scheme_name, work_status, latitude FROM "
                + "tenant_as.scheme_master_table WHERE id = ?", legacy))
                .containsEntry("state_scheme_code", "SCH-000008").containsEntry("scheme_name", "CHAPATOLI PWSS")
                .containsEntry("work_status", 2).containsEntry("latitude", 26.5);
        assertThat(report.get("schemes.matched.by_id_pair")).isEqualTo(1);
        assertThat(report.issues()).extracting(SyncIssue::category).containsExactly("UNKNOWN_WORK_STATUS");
    }

    @Test
    void adoptsTheMissingIdWhenOnlyOneIdIsKnown() {
        int legacy = scheme("OLD-SMT", "8165607", "Chapatoli", null);

        sync(chapatoli(List.of(), List.of()));

        assertThat(jdbc.queryForObject("SELECT state_scheme_id FROM tenant_as.scheme_master_table WHERE id = ?",
                String.class, legacy)).isEqualTo("34123");
    }

    @Test
    void idsPointingAtTwoDifferentSchemesAreAConflictNotAWrite() {
        scheme("1", "8165607", "By centre", null);
        scheme("34123", "999", "By state", null);

        SyncReport report = sync(chapatoli(List.of(), List.of()));

        assertThat(report.issues()).extracting(SyncIssue::category).containsExactly("CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES");
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_master_table")).isEqualTo(2);
    }

    @Test
    void aRemovedOfficerIsRetiredAndRevivedOnReturn() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        int id = schemeId("SCH-000008");
        int poMapping = jdbc.queryForObject("SELECT m.id FROM tenant_as.user_scheme_mapping_table m JOIN tenant_as.user_table u "
                + "ON u.id = m.user_id WHERE u.state_user_id = 'USR-021920'", Integer.class);

        SyncReport removed = sync(chapatoli(List.of("VIL-008633"), List.of(so)));
        assertThat(liveOfficers(id)).isEqualTo(1);
        assertThat(removed.get("user_scheme_mappings.retired")).isEqualTo(1);

        SyncReport back = sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        assertThat(liveOfficers(id)).isEqualTo(2);
        assertThat(back.get("user_scheme_mappings.revived")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.user_scheme_mapping_table WHERE scheme_id = ?", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM tenant_as.user_scheme_mapping_table WHERE id = ?",
                Integer.class, poMapping)).isEqualTo(1);
    }

    @Test
    void anEmptyListKeepsWhatWeHoldUnlessConfiguredOtherwise() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        int id = schemeId("SCH-000008");

        UpstreamScheme empty = upstream("SCH-000008", "8165607", "34123", "CHAPATOLI PWSS", List.of(), List.of(), List.of());
        SyncReport kept = sync(empty);

        assertThat(liveOfficers(id)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? AND deleted_at IS NULL", id)).isEqualTo(1);
        assertThat(kept.issues()).extracting(SyncIssue::category).containsExactlyInAnyOrder(
                "EMPTY_VILLAGE_LIST_KEPT", "EMPTY_SUBDIVISION_LIST_KEPT", "EMPTY_OFFICER_LIST_KEPT");

        properties.setRetireOnEmptyList(true);
        sync(empty);
        assertThat(liveOfficers(id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? AND deleted_at IS NULL", id)).isZero();
    }

    @Test
    void anUnresolvedVillageOrOfficerKeepsTheirSideOfTheMappings() {
        sync(chapatoli(List.of("VIL-008633", "VIL-008635"), List.of(so, po)));
        int id = schemeId("SCH-000008");
        UpstreamPerson newPoBadPhone = person("USR-099999", "New Operator", "12345", "jal-mitra");

        SyncReport report = sync(chapatoli(List.of("VIL-008633", "VIL-UNKNOWN"), List.of(so, newPoBadPhone)));

        // VIL-008635 is not retired because the list contained a code we could not resolve...
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? AND deleted_at IS NULL", id)).isEqualTo(2);
        // ...and the old pump operator stays because the new one could not be written.
        assertThat(liveOfficers(id)).isEqualTo(2);
        assertThat(report.issues()).extracting(SyncIssue::category).containsExactlyInAnyOrder("VILLAGE_UNRESOLVED", "INVALID_PHONE");
        assertThat(report.issues()).allSatisfy(i -> assertThat(i.detail().toString()).doesNotContain("12345"));
    }

    @Test
    void anExistingUserIsMatchedByPhoneAndAPromotionIsWithheld() {
        int existing = user("thagen saikia", "91" + SO_PHONE, "SECTION_OFFICER", null);
        UpstreamPerson promoted = person("USR-016363", "Thagen Saikia", SO_PHONE, "executive-engineer");

        SyncReport report = sync(chapatoli(List.of(), List.of(promoted)));

        Map<String, Object> row = jdbc.queryForMap("SELECT u.state_user_id, ut.c_name FROM tenant_as.user_table u "
                + "JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type WHERE u.id = ?", existing);
        assertThat(row).containsEntry("state_user_id", "USR-016363").containsEntry("c_name", "SECTION_OFFICER");
        assertThat(report.issues()).extracting(SyncIssue::category).containsExactly("PROMOTION_WITHHELD");
        assertThat(report.get("users.name_updated")).isZero(); // same name, different case
        assertThat(count("SELECT COUNT(*) FROM tenant_as.user_table")).isEqualTo(2); // actor + existing
    }

    @Test
    void aNewSchemeWithoutVillagesSitsUnderTheStateUntilVillagesArrive() {
        sync(chapatoli(List.of(), List.of()));
        int id = schemeId("SCH-000008");
        assertThat(jdbc.queryForList("SELECT parent_lgd_id FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? "
                + "AND deleted_at IS NULL", Integer.class, id)).containsExactly(stateLgd);

        sync(chapatoli(List.of("VIL-008633"), List.of()));

        assertThat(jdbc.queryForList("SELECT parent_lgd_id FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? "
                + "AND deleted_at IS NULL", Integer.class, id)).containsExactly(village1);
    }

    @Test
    void anArchivedSchemeLosesItsOfficersUnlessItIsStillReporting() {
        sync(chapatoli(List.of(), List.of(so, po)),
                upstream("SCH-000009", "8165608", "34124", "OTHER PWSS", List.of(), List.of(), List.of(so)));
        int quiet = schemeId("SCH-000008");
        int reporting = schemeId("SCH-000009");
        reading(reporting, LocalDateTime.now().minusDays(3));

        SchemeReconciler reconciler = reconciler();
        SyncReport report = new SyncReport();
        reconciler.applyArchived(List.of("SCH-000008", "SCH-000009", "SCH-404"), report);

        assertThat(liveOfficers(quiet)).isZero();
        assertThat(liveOfficers(reporting)).isEqualTo(1);
        assertThat(report.issues()).extracting(SyncIssue::category).containsExactly("ARCHIVED_BUT_REPORTING");
        assertThat(report.get("archived.not_ours")).isEqualTo(1);
        // The scheme itself and its locations stay.
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_master_table WHERE deleted_at IS NULL")).isEqualTo(2);
    }

    @Test
    void anInactiveOfficerTheSchemeListsIsReactivatedAndRelinked() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        int id = schemeId("SCH-000008");
        // Deactivated here earlier (by an admin, or a block JJM has since lifted), link retired with it.
        jdbc.update("UPDATE tenant_as.user_table SET status = 0 WHERE state_user_id = 'USR-021920'");
        jdbc.update("UPDATE tenant_as.user_scheme_mapping_table m SET status = 0, deleted_at = NOW() FROM tenant_as.user_table u "
                + "WHERE u.id = m.user_id AND u.state_user_id = 'USR-021920'");

        SyncReport report = sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));

        assertThat(userStatus("USR-021920")).isEqualTo(1);
        assertThat(liveOfficers(id)).isEqualTo(2);
        assertThat(report.get("users.reactivated")).isEqualTo(1);
        assertThat(report.get("user_scheme_mappings.revived")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.user_scheme_mapping_table WHERE scheme_id = ?", id)).isEqualTo(2);
    }

    @Test
    void anOfficerListedOnTwoSchemesIsReactivatedOnce() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        jdbc.update("UPDATE tenant_as.user_table SET status = 0 WHERE state_user_id = 'USR-021920'");
        UpstreamScheme other = upstream("SCH-000009", "8165608", "34124", "OTHER PWSS", List.of("SDV-035"),
                List.of("VIL-008635"), List.of(po));

        SyncReport report = sync(chapatoli(List.of("VIL-008633"), List.of(so, po)), other);

        assertThat(report.get("users.reactivated")).isEqualTo(1);
        assertThat(liveOfficers(schemeId("SCH-000009"))).isEqualTo(1);
    }

    @Test
    void aBlockedOfficerStaysInactiveAndIsNotLinked() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        int id = schemeId("SCH-000008");
        jdbc.update("UPDATE tenant_as.user_table SET status = 0 WHERE state_user_id = 'USR-021920'");
        jdbc.update("UPDATE tenant_as.user_scheme_mapping_table m SET status = 0, deleted_at = NOW() FROM tenant_as.user_table u "
                + "WHERE u.id = m.user_id AND u.state_user_id = 'USR-021920'");

        SyncReport report = syncWithBlocked(Set.of("USR-021920"), chapatoli(List.of("VIL-008633"), List.of(so, po)));

        assertThat(userStatus("USR-021920")).isZero();
        assertThat(liveOfficers(id)).isEqualTo(1);
        assertThat(report.get("user_scheme_mappings.skipped_blocked_user")).isEqualTo(1);
        assertThat(report.counts()).doesNotContainKey("users.reactivated");
    }

    @Test
    void anActiveOfficerJjmHasBlockedIsDeactivatedAndUnlinked() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        int id = schemeId("SCH-000008");

        SyncReport report = syncWithBlocked(Set.of("USR-021920"), chapatoli(List.of("VIL-008633"), List.of(so, po)));

        assertThat(userStatus("USR-021920")).isZero();
        assertThat(liveOfficers(id)).isEqualTo(1);
        assertThat(report.get("users.blocked")).isEqualTo(1);
        assertThat(report.get("user_scheme_mappings.retired")).isEqualTo(1);
    }

    @Test
    void anInactiveAdminAccountIsNeverReactivated() {
        int adminType = jdbc.queryForObject("SELECT id FROM common_schema.user_type_master_table WHERE c_name = 'STATE_ADMIN'",
                Integer.class);
        jdbc.update("INSERT INTO tenant_as.user_table (tenant_id, title, email, user_type, phone_number, phone_number_hash, "
                        + "state_user_id, password, status, created_by, updated_by) VALUES (?, ?, 'admin@example.org', ?, ?, ?, "
                        + "'USR-016363', 'x', 0, ?, ?)",
                TENANT_ID, pii.encrypt("Thagen Saikia"), adminType, pii.encrypt("91" + SO_PHONE), pii.hmac("91" + SO_PHONE),
                actor, actor);

        SyncReport report = sync(chapatoli(List.of("VIL-008633"), List.of(so)));

        assertThat(userStatus("USR-016363")).isZero();
        assertThat(liveOfficers(schemeId("SCH-000008"))).isZero();
        assertThat(report.issues()).extracting(SyncIssue::category).contains("REACTIVATION_ON_ADMIN_WITHHELD");
    }

    @Test
    void aBlockedUserIsDeactivatedAndUnmapped() {
        sync(chapatoli(List.of(), List.of(so, po)));
        UserDirectory users = new UserDirectory(repository, pii, SCHEMA, TENANT_ID, actor);
        UserSchemeMappings mappings = new UserSchemeMappings(repository, SCHEMA, actor);
        SyncReport report = new SyncReport();

        Optional<StateSyncTenantRepository.UserRow> blocked = users.block("USR-021920", report);
        blocked.ifPresent(u -> mappings.retireAllForUser(u.id(), report));

        assertThat(jdbc.queryForObject("SELECT status FROM tenant_as.user_table WHERE state_user_id = 'USR-021920'",
                Integer.class)).isZero();
        assertThat(liveOfficers(schemeId("SCH-000008"))).isEqualTo(1);
        assertThat(users.updatedIds()).containsExactly(blocked.orElseThrow().id());
    }

    private int placeholder(String stateId, String centreId) {
        return jdbc.queryForObject("INSERT INTO tenant_as.scheme_master_table (state_scheme_id, centre_scheme_id, "
                + "scheme_name, work_status, operating_status, is_auto_provisioned) VALUES (?, ?, "
                + "'Auto-provisioned scheme', 0, 0, TRUE) RETURNING id", Integer.class, stateId, centreId);
    }

    private void anomaly(int schemeId) {
        jdbc.update("INSERT INTO tenant_as.anomaly_table (user_id, scheme_id, type, status) VALUES (?, ?, 1, 1)", actor, schemeId);
    }

    private int rowsOf(String table, int schemeId) {
        return count("SELECT COUNT(*) FROM tenant_as." + table + " WHERE scheme_id = ?", schemeId);
    }

    @Test
    void aPlaceholdersReadingsMoveToTheRealSchemeAndThePlaceholderIsRetired() {
        int placeholder = placeholder("", "8165607");
        reading(placeholder, LocalDateTime.of(2026, 7, 10, 8, 0));
        reading(placeholder, LocalDateTime.of(2026, 8, 2, 8, 0));
        anomaly(placeholder);

        SchemeReconciler reconciler = reconciler();
        SyncReport report = new SyncReport();
        reconciler.reconcile(List.of(chapatoli(List.of(), List.of())), report);

        int real = schemeId("SCH-000008");
        assertThat(real).isNotEqualTo(placeholder);
        assertThat(rowsOf("flow_reading_table", real)).isEqualTo(2);
        assertThat(rowsOf("anomaly_table", real)).isEqualTo(1);
        assertThat(rowsOf("flow_reading_table", placeholder)).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM tenant_as.scheme_master_table WHERE id = ?",
                Boolean.class, placeholder)).isTrue();
        assertThat(reconciler.reassignments()).containsExactly(new SchemeReconciler.Reassignment(placeholder, real));
        assertThat(report.counts()).containsEntry("placeholders.readings_moved", 2).containsEntry("placeholders.retired", 1);
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.category()).isEqualTo("PLACEHOLDER_SUPERSEDED");
            assertThat(i.detail()).containsEntry("placeholderSchemeId", placeholder).containsEntry("moved", true);
        });
    }

    @Test
    void aPlaceholderStaysPutWhenTwoUpstreamSchemesShareItsImisId() {
        int placeholder = placeholder("", "8165607");
        reading(placeholder, LocalDateTime.of(2026, 7, 10, 8, 0));
        UpstreamScheme twin = upstream("SCH-000009", "8165607", "99999", "CHAPATOLI TWIN", List.of("SDV-035"),
                List.of(), List.of());

        SchemeReconciler reconciler = reconciler();
        SyncReport report = new SyncReport();
        reconciler.reconcile(List.of(chapatoli(List.of(), List.of()), twin), report);

        assertThat(rowsOf("flow_reading_table", placeholder)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM tenant_as.scheme_master_table WHERE id = ?",
                Boolean.class, placeholder)).isTrue();
        assertThat(reconciler.reassignments()).isEmpty();
        assertThat(report.issues()).filteredOn(i -> i.category().equals("PLACEHOLDER_IMIS_NOT_UNIQUE")).singleElement()
                .satisfies(i -> assertThat(i.detail()).containsEntry("placeholderSchemeIds", List.of(placeholder)));
    }

    @Test
    void aPlaceholderStaysPutWhenTwoOfOurSchemesCarryItsImisId() {
        scheme("34123", "8165607", "Chapatoli", null);
        scheme("77777", "8165607", "Chapatoli duplicate", null);
        int placeholder = placeholder("", "8165607");
        reading(placeholder, LocalDateTime.of(2026, 7, 10, 8, 0));

        SyncReport report = sync(chapatoli(List.of(), List.of()));

        assertThat(rowsOf("flow_reading_table", placeholder)).isEqualTo(1);
        assertThat(report.issues()).extracting(SyncIssue::category).contains("PLACEHOLDER_IMIS_NOT_UNIQUE")
                .doesNotContain("PLACEHOLDER_SUPERSEDED");
    }

    @Test
    void aPersonListedWithTwoRolesKeepsTheHigherInEitherOrder() {
        UpstreamPerson asSo = person("USR-047218", "Nibir Paban Borah", "9100000005", "section-officer");
        UpstreamPerson asSdo = person("USR-047218", "Nibir Paban Borah", "9100000005", "sdo");
        UpstreamScheme first = upstream("SCH-000010", "8100010", "40010", "FIRST", List.of(), List.of(), List.of(asSdo));
        UpstreamScheme second = upstream("SCH-000011", "8100011", "40011", "SECOND", List.of(), List.of(), List.of(asSo));

        SyncReport report = sync(first, second);

        assertThat(roleOf("USR-047218")).isEqualTo("SUB_DIVISIONAL_OFFICER");
        assertThat(report.get("users.role_kept_higher")).isEqualTo(1);
        assertThat(report.issues()).extracting(SyncIssue::category).doesNotContain("ROLE_CONFLICT_IN_UPSTREAM");

        sync(second, first);
        assertThat(roleOf("USR-047218")).isEqualTo("SUB_DIVISIONAL_OFFICER");
        assertThat(liveOfficers(schemeId("SCH-000010"))).isEqualTo(1);
        assertThat(liveOfficers(schemeId("SCH-000011"))).isEqualTo(1);
    }

    @Test
    void anHourlyOrRefreshRunNeverLowersARoleButTheNightlyRunDoes() {
        UpstreamPerson asSdo = person("USR-047387", "Nibir Paban Borah", "9100000006", "sdo");
        UpstreamPerson asSo = person("USR-047387", "Nibir Paban Borah", "9100000006", "section-officer");
        sync(upstream("SCH-000012", "8100012", "40012", "ONE", List.of(), List.of(), List.of(asSdo)));

        SyncReport partial = syncAllowingDowngrade(false,
                upstream("SCH-000012", "8100012", "40012", "ONE", List.of(), List.of(), List.of(asSo)));
        assertThat(roleOf("USR-047387")).isEqualTo("SUB_DIVISIONAL_OFFICER");
        assertThat(partial.get("users.role_downgrade_deferred")).isEqualTo(1);

        syncAllowingDowngrade(true, upstream("SCH-000012", "8100012", "40012", "ONE", List.of(), List.of(), List.of(asSo)));
        assertThat(roleOf("USR-047387")).isEqualTo("SECTION_OFFICER");
    }

    @Test
    void aRoleOutsideTheUpstreamsValueSetIsReportedOnce() {
        UpstreamPerson odd = person("USR-090001", "Someone", "9100000007", "plumber");
        UpstreamPerson odd2 = person("USR-090002", "Someone Else", "9100000008", "plumber");
        UpstreamPerson khalasi = person("USR-090003", "A Khalasi", "9100000009", "khalasi");

        SyncReport report = sync(chapatoli(List.of(), List.of(so, odd, odd2, khalasi)));

        assertThat(report.issues()).filteredOn(i -> i.category().equals("UNKNOWN_ROLE")).singleElement()
                .satisfies(i -> assertThat(i.detail()).containsEntry("role", "plumber"));
        assertThat(count("SELECT COUNT(*) FROM tenant_as.user_table WHERE state_user_id IN ('USR-090001', 'USR-090003')")).isZero();
    }

    @Test
    void reportsOurSchemesJjmNeverMatchedForReview() {
        int local = scheme("60001", "9000002", "Local only PWSS", null);
        reading(local, LocalDateTime.of(2026, 9, 3, 8, 0));
        int placeholder = placeholder("", "9000003");

        SchemeReconciler reconciler = reconciler();
        SyncReport report = new SyncReport();
        reconciler.reconcile(List.of(chapatoli(List.of(), List.of())), report);
        reconciler.reportNotInUpstream(report);

        assertThat(report.issues()).filteredOn(i -> i.category().equals("LOCAL_SCHEME_NOT_IN_JJM")).singleElement()
                .satisfies(i -> assertThat(i.detail()).containsEntry("schemeId", local)
                        .containsEntry("schemeName", "Local only PWSS").containsEntry("centreSchemeId", "9000002")
                        .containsEntry("stateSchemeId", "60001").containsEntry("lastReadingDate", "2026-09-03"));
        assertThat(report.get("schemes.local_not_in_jjm")).isEqualTo(1);
        assertThat(report.issues()).noneMatch(i -> placeholder == (Integer) i.detail().getOrDefault("schemeId", -1));
    }

    @Test
    void aRetiredSchemeLosesItsOfficersAndComesBackWhenJjmListsItAgain() {
        sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));
        int id = schemeId("SCH-000008");

        SchemeReconciler reconciler = reconciler();
        SyncReport retire = new SyncReport();
        assertThat(reconciler.retireMissing(Set.of("SCH-000008"), retire)).containsExactly(id);

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL AND deleted_by = ? FROM tenant_as.scheme_master_table "
                + "WHERE id = ?", Boolean.class, actor, id)).isTrue();
        assertThat(liveOfficers(id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_lgd_mapping_table WHERE scheme_id = ? AND deleted_at IS NULL",
                id)).isEqualTo(1);
        assertThat(retire.issues()).extracting(SyncIssue::category).contains("SCHEME_RETIRED");

        SyncReport back = sync(chapatoli(List.of("VIL-008633"), List.of(so, po)));

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM tenant_as.scheme_master_table WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_master_table WHERE state_scheme_code = 'SCH-000008'"))
                .isEqualTo(1);
        assertThat(liveOfficers(id)).isEqualTo(2);
        assertThat(back.get("schemes.revived")).isEqualTo(1);
    }

    @Test
    void aSchemeAnAdminDeletedIsBroughtBackAndReportedWhenJjmStillListsIt() {
        int deleted = scheme("34123", "8165607", "CHAPATOLI PWSS", "SCH-000008");
        jdbc.update("UPDATE tenant_as.scheme_master_table SET deleted_at = NOW(), deleted_by = NULL WHERE id = ?", deleted);

        SyncReport report = sync(chapatoli(List.of(), List.of()));

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM tenant_as.scheme_master_table WHERE id = ?",
                Boolean.class, deleted)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_master_table WHERE state_scheme_code = 'SCH-000008'"))
                .isEqualTo(1);
        assertThat(report.get("schemes.revived")).isEqualTo(1);
        assertThat(report.issues()).filteredOn(i -> i.category().equals("SCHEME_REVIVED")).singleElement()
                .satisfies(i -> assertThat(i.detail()).containsEntry("schemeId", deleted).containsEntry("matchedBy", "code")
                        .containsEntry("retiredBySync", false).containsKey("deletedAt"));
    }

    @Test
    void aSchemeDeletedBeforeItHadAJjmCodeIsBroughtBackByItsIdPair() {
        int deleted = scheme("34123", "8165607", "Chapatoli", null);
        jdbc.update("UPDATE tenant_as.scheme_master_table SET deleted_at = NOW(), deleted_by = ? WHERE id = ?", actor, deleted);

        SyncReport report = sync(chapatoli(List.of(), List.of()));

        assertThat(schemeId("SCH-000008")).isEqualTo(deleted);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_master_table")).isEqualTo(1);
        assertThat(report.issues()).filteredOn(i -> i.category().equals("SCHEME_REVIVED")).singleElement()
                .satisfies(i -> assertThat(i.detail()).containsEntry("matchedBy", "id_pair").containsEntry("deletedBy", actor));
    }

    @Test
    void anExistingRealSchemeAlsoAbsorbsAPlaceholderWithItsImisId() {
        int real = scheme("34123", "8165607", "Chapatoli", "SCH-000008");
        int first = placeholder("34123", "8165607");
        int second = placeholder("", "8165607");
        reading(first, LocalDateTime.of(2026, 7, 10, 8, 0));
        reading(second, LocalDateTime.of(2026, 7, 11, 8, 0));

        SchemeReconciler reconciler = reconciler();
        reconciler.reconcile(List.of(chapatoli(List.of(), List.of())), new SyncReport());

        assertThat(rowsOf("flow_reading_table", real)).isEqualTo(2);
        assertThat(reconciler.reassignments()).extracting(SchemeReconciler.Reassignment::fromSchemeId)
                .containsExactlyInAnyOrder(first, second);
        // A second run finds nothing left to move.
        SchemeReconciler again = reconciler();
        again.reconcile(List.of(chapatoli(List.of(), List.of())), new SyncReport());
        assertThat(again.reassignments()).isEmpty();
    }

    @Test
    void withTheMoveSwitchedOffAPlaceholderIsOnlyReported() {
        properties.setMovePlaceholderReadings(false);
        int placeholder = placeholder("", "8165607");
        reading(placeholder, LocalDateTime.of(2026, 7, 10, 8, 0));

        SchemeReconciler reconciler = reconciler();
        SyncReport report = new SyncReport();
        reconciler.reconcile(List.of(chapatoli(List.of(), List.of())), report);

        assertThat(rowsOf("flow_reading_table", placeholder)).isEqualTo(1);
        assertThat(reconciler.reassignments()).isEmpty();
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.category()).isEqualTo("PLACEHOLDER_SUPERSEDED");
            assertThat(i.detail()).containsEntry("moved", false);
        });
    }

    private String snapshot() {
        return jdbc.queryForList("""
                SELECT 's' t, id, updated_at::text u, deleted_at::text d FROM tenant_as.scheme_master_table
                UNION ALL SELECT 'l', id, updated_at::text, deleted_at::text FROM tenant_as.scheme_lgd_mapping_table
                UNION ALL SELECT 'd', id, updated_at::text, deleted_at::text FROM tenant_as.scheme_department_mapping_table
                UNION ALL SELECT 'm', id, updated_at::text, deleted_at::text FROM tenant_as.user_scheme_mapping_table
                UNION ALL SELECT 'u', id, updated_at::text, deleted_at::text FROM tenant_as.user_table
                ORDER BY 1, 2
                """).toString();
    }
}
