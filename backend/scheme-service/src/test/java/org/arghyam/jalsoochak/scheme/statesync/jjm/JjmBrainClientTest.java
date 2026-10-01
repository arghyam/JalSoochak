package org.arghyam.jalsoochak.scheme.statesync.jjm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JjmBrainClient} against WireMock, with payloads taken from the JJM Brain Arghyam API
 * documentation v1.0 (September 2026) — including its inconsistencies.
 */
class JjmBrainClientTest {

    private static final String API_KEY = "test-key";

    private WireMockServer wireMock;
    private JjmBrainClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        StateSyncProperties.Jjm settings = new StateSyncProperties.Jjm();
        settings.setBaseUrl(wireMock.baseUrl() + "/api/v1");
        settings.setApiKey(API_KEY);
        settings.setMinRequestInterval(Duration.ZERO);
        settings.setInitialBackoff(Duration.ofSeconds(2));
        settings.setMaxAttempts(3);
        client = new JjmBrainClient(settings, new ObjectMapper(), sleeps::add);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        StateSyncProperties.Jjm settings = new StateSyncProperties.Jjm();
        assertThatThrownBy(() -> new JjmBrainClient(settings, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JJM_BRAIN_API_KEY");
    }

    @Test
    void sendsTheApiKeyAndReadsCirclesWithTheirZone() {
        stub("/api/v1/arghyam/circle-master", 1, """
                {"status":200,"message":"Circle lists","data":{"data":[
                  {"code":"CIR-008","name":"Cachar Circle","zone":{"code":"ZON-004","name":"Barak Valley Zone"}},
                  {"code":"CIR-005","name":"Dibrugarh Circle","zone":{"code":"ZON-003","name":"Upper Assam Zone"}}],
                 "links":{"first":"https://jjmbrain.in/api/v1/arghyam/circle-master?page=1","next":null},
                 "meta":{"current_page":1,"from":1,"last_page":1,"per_page":100,"to":2,"total":2}}}
                """);

        List<UpstreamNode> circles = client.circles();

        assertThat(circles).containsExactly(
                new UpstreamNode("CIR-008", "Cachar Circle", "ZON-004"),
                new UpstreamNode("CIR-005", "Dibrugarh Circle", "ZON-003"));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/arghyam/circle-master"))
                .withHeader("x-api-key", equalTo(API_KEY))
                .withQueryParam("page", equalTo("1")));
    }

    @Test
    void divisionsHangOffTheirCircleNotTheirZone() {
        stub("/api/v1/arghyam/division-master", 1, """
                {"status":200,"data":{"data":[{"code":"DIV-010","name":"Bajali Division",
                  "zone":{"code":"ZON-001","name":"Lower Assam Zone"},
                  "circle":{"code":"CIR-002","name":"Nalbari Circle"},
                  "executive_engineers":[{"code":"USR-047217","name":"EE","phone":"9000000001","role":"executive-engineer"}]}],
                 "meta":{"current_page":1,"last_page":1,"per_page":100,"total":1}}}
                """);

        assertThat(client.divisions()).containsExactly(new UpstreamNode("DIV-010", "Bajali Division", "CIR-002"));
    }

    @Test
    void acceptsAFlatParentCodeToo() {
        stub("/api/v1/arghyam/subdivision-master", 1, """
                {"status":200,"data":{"data":[{"code":"SDV-039","name":"Amguri","division_code":"DIV-022"}],
                 "links":{"first":"","last":"","prev":null,"next":null},"meta":{"current_page":1,"per_page":100,"total":1}}}
                """);

        assertThat(client.subdivisions()).containsExactly(new UpstreamNode("SDV-039", "Amguri", "DIV-022"));
    }

    @Test
    void pagesUntilAShortPageWithoutFollowingTheLocalhostLinks() {
        // 100 villages on page 1, 2 on page 2; links point at 127.0.0.1:8000 as in the published sample.
        String page1 = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> village("VIL-%06d".formatted(i)))
                .collect(Collectors.joining(","));
        stub("/api/v1/arghyam/village-master", 1, """
                {"status":200,"data":{"data":[%s],
                 "links":{"next":"http://127.0.0.1:8000/api/v1/arghyam/village-master?page=2"},
                 "meta":{"current_page":1,"per_page":100,"total":1}}}
                """.formatted(page1));
        stub("/api/v1/arghyam/village-master", 2, """
                {"status":200,"data":{"data":[%s,%s],
                 "links":{"next":null},"meta":{"current_page":2,"per_page":100,"total":1}}}
                """.formatted(village("VIL-000100"), village("VIL-000101")));

        List<UpstreamNode> villages = client.villages();

        // meta.total (1) is ignored; the row repeated across pages is kept once.
        assertThat(villages).hasSize(101);
        assertThat(villages.get(0).parentCode()).isEqualTo("PAN-00103");
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/arghyam/village-master"))
                .withQueryParam("page", equalTo("3")));
    }

    @Test
    void stopsAtLastPageEvenWhenThePageIsFull() {
        String full = IntStream.rangeClosed(1, 100).mapToObj(i -> village("VIL-%06d".formatted(i)))
                .collect(Collectors.joining(","));
        stub("/api/v1/arghyam/village-master", 1, """
                {"status":200,"data":{"data":[%s],"meta":{"current_page":1,"last_page":1,"per_page":100,"total":100}}}
                """.formatted(full));

        assertThat(client.villages()).hasSize(100);
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/arghyam/village-master"))
                .withQueryParam("page", equalTo("2")));
    }

    @Test
    void readsTheSchemeSample() {
        stub("/api/v1/arghyam/schemes", 1, SCHEME_SAMPLE);

        List<UpstreamScheme> schemes = client.schemes(null);

        assertThat(schemes).hasSize(1);
        UpstreamScheme s = schemes.get(0);
        assertThat(s.code()).isEqualTo("SCH-000008");
        assertThat(s.centreSchemeId()).isEqualTo("8165607");
        assertThat(s.stateSchemeId()).isEqualTo("10"); // an integer on the wire, a string for us
        assertThat(s.name()).isEqualTo("CHAPATOLI PWSS");
        assertThat(s.workStatus()).isEqualTo("handed-over");
        assertThat(s.operatingStatus()).isEqualTo("non-operative");
        assertThat(s.plannedFhtc()).isEqualTo(280);
        assertThat(s.achievedFhtc()).isEqualTo(241);
        assertThat(s.latitude()).isEqualTo("27.15429600");
        assertThat(s.subdivisionCodes()).containsExactly("SDV-035");
        assertThat(s.villageCodes()).containsExactly("VIL-008633", "VIL-008635");
        assertThat(s.updatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 19, 11, 47, 13));
        // Officers appear both nested and flat; each person is listed once, with their own role.
        assertThat(s.officers()).extracting(UpstreamPerson::code, UpstreamPerson::role)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("USR-047387", "sdo"),
                        org.assertj.core.groups.Tuple.tuple("USR-047218", "executive-engineer"),
                        org.assertj.core.groups.Tuple.tuple("USR-016363", "section-officer"),
                        org.assertj.core.groups.Tuple.tuple("USR-021920", "jal-mitra"));
    }

    @Test
    void sendsUpdatedSinceAsTheDocumentedDateTime() {
        stub("/api/v1/arghyam/schemes", 1, "{\"status\":200,\"data\":{\"data\":[],\"meta\":{\"per_page\":100}}}");

        assertThat(client.schemes(LocalDateTime.of(2026, 8, 23, 7, 5, 0))).isEmpty();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/arghyam/schemes"))
                .withQueryParam("updated_since", equalTo("2026-08-23 07:05:00")));
    }

    @Test
    void looksASchemeUpByCodeAndByImisId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/schemes"))
                .withQueryParam("code", equalTo("SCH-000008")).willReturn(okJson(SCHEME_SAMPLE)));
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/schemes"))
                .withQueryParam("centre_scheme_id", equalTo("8165607")).willReturn(okJson(SCHEME_SAMPLE)));

        assertThat(client.schemeByCode("SCH-000008")).map(UpstreamScheme::code).contains("SCH-000008");
        assertThat(client.schemeByCentreSchemeId("8165607")).map(UpstreamScheme::code).contains("SCH-000008");
    }

    @Test
    void readsTheUnpaginatedCodeLists() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/archived-schemes")).willReturn(okJson("""
                {"status":200,"message":"Archived scheme codes","data":["SCH-000137","SCH-000503"," "]}
                """)));
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/blocked-users")).willReturn(okJson("""
                {"status":200,"message":"Blocked user codes","data":["USR-015756","USR-015756"]}
                """)));

        assertThat(client.archivedSchemeCodes()).containsExactly("SCH-000137", "SCH-000503");
        assertThat(client.blockedUserCodes()).containsExactly("USR-015756");
    }

    @Test
    void retriesAServerErrorWithBackoffThenSucceeds() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/zone-master")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/zone-master")).inScenario("flaky")
                .whenScenarioStateIs("second").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7"))
                .willSetStateTo("ok"));
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/zone-master")).inScenario("flaky")
                .whenScenarioStateIs("ok").willReturn(okJson("""
                        {"status":200,"data":{"data":[{"code":"ZON-004","name":"Barak Valley Zone"}],"meta":{"per_page":100}}}
                        """)));

        assertThat(client.zones()).containsExactly(new UpstreamNode("ZON-004", "Barak Valley Zone", null));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(7));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/zone-master")).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> client.zones())
                .isInstanceOf(StateMasterDataException.class)
                .hasMessageContaining("HTTP 502 after 3 attempt(s)");
    }

    @Test
    void failsAtOnceOnARejectedKey() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/arghyam/zone-master"))
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Unauthorized: Invalid API Key\"}")));

        assertThatThrownBy(() -> client.zones())
                .isInstanceOf(StateMasterDataException.class)
                .hasMessageContaining("rejected the API key");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void parsesAnIsoTimestampWithOffsetIntoIst() {
        assertThat(JjmBrainClient.timestamp("2026-07-01T09:52:44Z")).isEqualTo(LocalDateTime.of(2026, 7, 1, 15, 22, 44));
        assertThat(JjmBrainClient.timestamp("not a date")).isNull();
    }

    private void stub(String path, int page, String body) {
        wireMock.stubFor(get(urlPathEqualTo(path)).withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(okJson(body)));
    }

    private static String village(String code) {
        return "{\"code\":\"%s\",\"name\":\"V %s\",\"panchayat\":{\"code\":\"PAN-00103\",\"name\":\"BAMUNKUCHI\"}}"
                .formatted(code, code);
    }

    static final String SCHEME_SAMPLE = """
            {"status":200,"message":"Scheme lists","data":{"data":[{
              "code":"SCH-000008","centre_scheme_id":"8165607","state_scheme_id":10,"name":"CHAPATOLI PWSS",
              "work_status":"handed-over","operating_status":"non-operative","funding_agency":"JJM",
              "division":{"code":"DIV-020","name":"Dibrugarh Division","executive_engineers":[
                {"code":"USR-047218","name":"NIBIR PABAN BORAH","phone":"7002352190","role":"executive-engineer"}]},
              "subdivisions":[{"code":"SDV-035","name":"Naharkatia","sdos":[
                {"code":"USR-047387","name":"NIBIR PABAN BORAH","phone":"9864202057","role":"sdo"}]}],
              "district":{"code":"DST-061","name":"Dibrugarh"},
              "block":[{"code":"BLK-0089","name":"TINGKHONG"}],
              "panchayat":[{"code":"PAN-00994","name":"DILLIBARI"}],
              "village":[{"code":"VIL-008633","name":"CHAPATALI NO.2"},{"code":"VIL-008635","name":"CHAPATALI NO.4"}],
              "planned_fhtc_imis":280,"achieved_fhtc_imis":241,"latitude":"27.15429600","longitude":"95.16469400",
              "section-officer":[{"code":"USR-016363","name":"Thagen Saikia","phone":"9954443768","role":"section-officer"}],
              "executive-engineer":[{"code":"USR-047218","name":"NIBIR PABAN BORAH","phone":"7002352190","role":"executive-engineer"}],
              "sdo":[{"code":"USR-047387","name":"NIBIR PABAN BORAH","phone":"9864202057","role":"sdo"}],
              "pump-operator":[{"code":"USR-021920","name":"BOLIN GOGOI","phone":"9954155775","role":"jal-mitra"}],
              "updated_at":"2026-06-19 11:47:13"}],
             "links":{"first":"https://jjmbrain.in/api/v1/arghyam/schemes?page=1","next":null},
             "meta":{"current_page":1,"from":1,"last_page":1,"per_page":100,"to":1,"total":1}}}
            """;
}
