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

    @Test
    void anAutoProvisionedPlaceholderIsNeverMatchedButIsFlagged() {
        int placeholder = jdbc.queryForObject("INSERT INTO tenant_as.scheme_master_table (state_scheme_id, centre_scheme_id, "
                + "scheme_name, work_status, operating_status, is_auto_provisioned) VALUES ('34123', '8165607', "
                + "'Auto-provisioned scheme', 0, 0, TRUE) RETURNING id", Integer.class);

        SyncReport report = sync(chapatoli(List.of(), List.of()));

        assertThat(schemeId("SCH-000008")).isNotEqualTo(placeholder);
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.category()).isEqualTo("PLACEHOLDER_SUPERSEDED");
            assertThat(i.detail()).containsEntry("placeholderSchemeId", placeholder);
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
