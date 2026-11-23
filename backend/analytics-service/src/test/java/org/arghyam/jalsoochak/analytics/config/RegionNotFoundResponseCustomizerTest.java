package org.arghyam.jalsoochak.analytics.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RegionNotFoundResponseCustomizerTest {

    private final RegionNotFoundResponseCustomizer customizer = new RegionNotFoundResponseCustomizer();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"byLgdId", "byParentLgdId", "byDepartmentId", "byParentDepartmentId", "byValueAlias"})
    void operationTakingARegionParam_documentsTheGuardsNotFound(String handler) {
        Operation operation = customizer.customize(operationWithOk(), handlerMethod(handler));

        ApiResponse notFound = operation.getResponses().get("404");
        assertThat(notFound).isNotNull();
        assertThat(notFound.getDescription()).contains("phased rollout");
        MediaType json = notFound.getContent().get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        assertThat(json.getExamples().get("failure").getValue().toString()).contains("Region not found");
        assertThat(operation.getResponses()).containsKey("200");
    }

    @Test
    void operationWithoutARegionParam_isLeftAlone() {
        Operation operation = customizer.customize(operationWithOk(), handlerMethod("byTenantOnly"));

        assertThat(operation.getResponses()).containsOnlyKeys("200");
    }

    @Test
    void operationThatDocumentsItsOwnNotFound_keepsIt() {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("404", new ApiResponse().description("The parent isn't onboarded"));
        Operation operation = new Operation().responses(responses);

        customizer.customize(operation, handlerMethod("byParentLgdId"));

        assertThat(operation.getResponses().get("404").getDescription()).isEqualTo("The parent isn't onboarded");
    }

    @Test
    void operationWithoutResponses_getsTheNotFound() {
        Operation operation = customizer.customize(new Operation(), handlerMethod("byLgdId"));

        assertThat(operation.getResponses()).containsOnlyKeys("404");
    }

    @Test
    void eachOperationGetsItsOwnResponse_soEditingOneCannotChangeAnother() {
        Operation first = customizer.customize(operationWithOk(), handlerMethod("byLgdId"));
        Operation second = customizer.customize(operationWithOk(), handlerMethod("byDepartmentId"));

        assertThat(first.getResponses().get("404")).isNotSameAs(second.getResponses().get("404"));
    }

    private static Operation operationWithOk() {
        return new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
    }

    private static HandlerMethod handlerMethod(String name) {
        return Arrays.stream(StandInController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .map(method -> new HandlerMethod(new StandInController(), method))
                .orElseThrow();
    }

    @SuppressWarnings("unused")
    static class StandInController {

        void byLgdId(@RequestParam(name = "tenant_id") Integer tenantId,
                     @RequestParam(name = "lgd_id") Integer lgdId) {
        }

        void byParentLgdId(@RequestParam(name = "tenant_id") Integer tenantId,
                           @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId) {
        }

        void byDepartmentId(@RequestParam(name = "department_id") Integer departmentId) {
        }

        void byParentDepartmentId(@RequestParam(name = "parent_department_id") Integer parentDepartmentId) {
        }

        void byValueAlias(@RequestParam("lgd_id") Integer lgdId) {
        }

        void byTenantOnly(@RequestParam(name = "tenant_id") Integer tenantId) {
        }
    }
}
