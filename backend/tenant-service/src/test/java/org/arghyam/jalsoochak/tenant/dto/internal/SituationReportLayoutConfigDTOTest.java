package org.arghyam.jalsoochak.tenant.dto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.FooterElementDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.HeaderElementDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportLayoutDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportSectionDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.SpacerDTO;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;

/**
 * Binding and serialisation of {@link SituationReportLayoutConfigDTO}: what the config write path
 * stores is what was sent, and what message-service reads back.
 */
@DisplayName("SituationReportLayoutConfigDTO")
class SituationReportLayoutConfigDTOTest {

    /** Numbers are equal by value: a width sent as 70 is stored as 70.0. */
    private static final Comparator<JsonNode> NUMERIC_VALUE = (a, b) ->
            a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @ParameterizedTest
    @ValueSource(strings = {"custom-layout-sample.json", "daily-section-officer.json", "weekly-section-officer.json",
            "weekly-sub-divisional-officer.json"})
    @DisplayName("a document survives bind and re-serialisation unchanged, polymorphic elements included")
    void roundTrip(String file) throws IOException {
        JsonNode sent = document(file);

        SituationReportLayoutConfigDTO bound = objectMapper.treeToValue(sent, SituationReportLayoutConfigDTO.class);
        String stored = objectMapper.writeValueAsString(bound);

        assertThat(objectMapper.readTree(stored).equals(NUMERIC_VALUE, sent)).as(stored).isTrue();
        assertThat(objectMapper.readValue(stored, SituationReportLayoutConfigDTO.class)).isEqualTo(bound);
    }

    @Test
    @DisplayName("absent fields stay absent: nothing is stored as null")
    void nonNull() throws IOException {
        String sent = "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"layout\":{\"header\":["
                + "{\"type\":\"DASHBOARD_LINK\",\"prefix\":\"See \"},{\"type\":\"SPACER\"}],"
                + "\"sections\":[{\"type\":\"SCHEME_LIST\",\"list\":\"NO_SUPPLY\",\"title\":\"T\","
                + "\"columns\":[{\"field\":\"SCHEME_NAME\",\"label\":\"S\"}]}]}}}}}";

        SituationReportLayoutConfigDTO bound = objectMapper.readValue(sent, SituationReportLayoutConfigDTO.class);

        assertThat(objectMapper.writeValueAsString(bound)).isEqualTo(sent);
    }

    @Test
    @DisplayName("the roles keep the document's order, since they are the recipients in that order")
    void roleOrder() throws IOException {
        SituationReportLayoutConfigDTO bound = objectMapper.readValue("{\"version\":1,\"weekly\":{\"roles\":{"
                + "\"SUB_DIVISIONAL_OFFICER\":{\"useDefault\":true},\"SECTION_OFFICER\":{\"useDefault\":true}}}}",
                SituationReportLayoutConfigDTO.class);

        assertThat(bound.weekly().roles().keySet())
                .containsExactly(ReportRole.SUB_DIVISIONAL_OFFICER, ReportRole.SECTION_OFFICER);
        assertThat(objectMapper.writeValueAsString(bound))
                .contains("{\"SUB_DIVISIONAL_OFFICER\":{\"useDefault\":true},"
                        + "\"SECTION_OFFICER\":{\"useDefault\":true}}");
    }

    @Test
    @DisplayName("each element binds to the type its 'type' names")
    void polymorphicTypes() throws IOException {
        ReportLayoutDTO layout = objectMapper.treeToValue(document("custom-layout-sample.json")
                .at("/daily/roles/SUB_DIVISIONAL_OFFICER/layout"), ReportLayoutDTO.class);

        assertThat(layout.header()).hasExactlyElementsOfTypes(HeaderElementDTO.Title.class,
                HeaderElementDTO.Subtitle.class, HeaderElementDTO.Field.class, HeaderElementDTO.Field.class);
        assertThat(layout.sections()).hasExactlyElementsOfTypes(
                ReportSectionDTO.SummarySection.class, ReportSectionDTO.SchemeListSection.class,
                ReportSectionDTO.SchemeListSection.class);
        assertThat(layout.footer()).hasExactlyElementsOfTypes(FooterElementDTO.Note.class,
                SpacerDTO.class, FooterElementDTO.SupportContact.class);
    }

    @Test
    @DisplayName("an unknown element type fails the bind")
    void unknownType() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"header\":[{\"type\":\"IMAGE\"}]}", ReportLayoutDTO.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    @DisplayName("a null element binds, for the validator to report, and the lists are read-only")
    void nullElements() throws IOException {
        ReportLayoutDTO layout = objectMapper.readValue("{\"header\":[null],\"sections\":[]}", ReportLayoutDTO.class);

        assertThat(layout.header()).containsExactly((HeaderElementDTO) null);
        assertThat(layout.footer()).isNull();
        List<ReportSectionDTO> sections = layout.sections();
        assertThatThrownBy(() -> sections.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    /** The sample as is; a built-in layout as the daily or weekly layout of its role. */
    private JsonNode document(String file) throws IOException {
        JsonNode content;
        try (InputStream in = getClass().getResourceAsStream("/report-layouts/" + file)) {
            assertThat(in).as(file).isNotNull();
            content = objectMapper.readTree(in);
        }
        if (content.has("version")) {
            return content;
        }
        String report = file.startsWith("daily") ? "daily" : "weekly";
        String role = file.contains("sub-divisional") ? "SUB_DIVISIONAL_OFFICER" : "SECTION_OFFICER";
        return objectMapper.readTree("{\"version\":1,\"" + report + "\":{\"roles\":{\"" + role + "\":{\"layout\":"
                + content + "}}}}");
    }
}
