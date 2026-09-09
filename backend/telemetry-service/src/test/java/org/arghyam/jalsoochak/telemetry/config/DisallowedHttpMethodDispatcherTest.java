package org.arghyam.jalsoochak.telemetry.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Proves the fix against the real {@code DispatcherServlet}, offline.
 *
 * <p>The unit tests assert what the filter does; these assert what the framework would otherwise do,
 * which is the part the audit finding is actually about. Each pair runs the same request with and
 * without the filter, so the negative control documents the vulnerability rather than describing it.
 *
 * <p>The controller here is a stand-in for {@code GlificWebhookController} — a single POST mapping on
 * the audited path. Using the real controller would mean wiring its service dependencies to assert a
 * property of the dispatcher, not of the controller.
 */
@DisplayName("DisallowedHttpMethodFilter against the real dispatcher")
class DisallowedHttpMethodDispatcherTest {

    private static final String AUDITED_PATH = "/api/v1/telemetry/schemes";

    /**
     * A real {@code @EnableWebMvc} context rather than {@code standaloneSetup}, which registers only
     * {@code RequestMappingHandlerAdapter} and therefore throws "No adapter for handler
     * [...PreFlightHandler]" on a preflight instead of reproducing production's behaviour.
     * {@code WebMvcConfigurationSupport} contributes the {@code HttpRequestHandlerAdapter} that
     * serves it.
     *
     * <p>{@code FrameworkServlet.dispatchOptionsRequest} defaults to {@code false} in the field and
     * is flipped to {@code true} by Boot's {@code WebMvcProperties}, which MockMvc does not apply —
     * so without {@code dispatchOptions(true)} these tests would exercise the {@code super.doOptions}
     * fallback instead of the {@code HttpOptionsHandler} that production hits.
     */
    private static MockMvc mockMvc(boolean withFilter) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcOnly.class);
        context.refresh();

        var builder = MockMvcBuilders.webAppContextSetup(context).dispatchOptions(true);
        if (withFilter) {
            builder = builder.addFilter(filter());
        }
        return builder.build();
    }

    private static DisallowedHttpMethodFilter filter() {
        MethodGuardProperties properties = new MethodGuardProperties();
        properties.init();
        return new DisallowedHttpMethodFilter(properties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("BEFORE: the dispatcher answers OPTIONS with 200 and enumerates the methods")
    void withoutTheFilterTheDispatcherAnswersOptionsWith200AndAnAllowHeader() throws Exception {
        MvcResult result = mockMvc(false).perform(options(AUDITED_PATH)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("this is the audit finding: OPTIONS is served, not rejected")
                .isEqualTo(200);
        assertThat(result.getResponse().getHeader("Allow")).contains("POST");
        assertThat(result.getResponse().getHeaderNames()).contains("Accept-Patch");
    }

    @Test
    @DisplayName("AFTER: the same request is 405 with no method list")
    void withTheFilterTheSameRequestIs405WithNoAllowHeader() throws Exception {
        MvcResult result = mockMvc(true).perform(options(AUDITED_PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader("Allow")).isNull();
        assertThat(result.getResponse().getHeaderNames()).doesNotContain("Accept-Patch");
    }

    @Test
    @DisplayName("BEFORE: a same-origin preflight leaks a broader method list than the resource has")
    void withoutTheFilterASameOriginPreflightFallsThroughToTheServletsBroadAllowList() throws Exception {
        // CorsUtils.isCorsRequest compares scheme/host/port, so against MockHttpServletRequest's
        // default http://localhost:80 this is a pre-flight request that is not a CORS request.
        // DefaultCorsProcessor returns without writing Allow, containsHeader("Allow") is false, and
        // FrameworkServlet.doOptions falls through to HttpServlet.doOptions — which reflects over the
        // servlet hierarchy rather than the mapping, so it advertises methods this route does not
        // serve. This is the same fall-through that dispatch-options-request=false would make the
        // default, and it is why that property was not used as the fix.
        MvcResult result = mockMvc(false).perform(options(AUDITED_PATH)
                        .header("Origin", "http://localhost")
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Allow"))
                .as("the fall-through advertises methods the resource does not map")
                .contains("GET", "PUT", "DELETE");
    }

    @Test
    @DisplayName("AFTER: that preflight is 405 with no method list")
    void withTheFilterASameOriginPreflightIs405() throws Exception {
        MvcResult result = mockMvc(true).perform(options(AUDITED_PATH)
                        .header("Origin", "http://localhost")
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader("Allow")).isNull();
    }

    @Test
    @DisplayName("BEFORE: a wrong method on a real mapping still discloses the right one")
    void withoutTheFilterAMethodMismatchDisclosesTheSupportedMethod() throws Exception {
        MvcResult result = mockMvc(false).perform(get(AUDITED_PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader("Allow"))
                .as("an OPTIONS-only fix would leave this disclosure open")
                .contains("POST");
    }

    @Test
    @DisplayName("AFTER: the mismatch is still 405, now without the method list")
    void withTheFilterAMethodMismatchIs405WithNoAllowHeader() throws Exception {
        // The status is deliberately unchanged — only the Allow header goes. This is the knowing
        // deviation from RFC 9110 §15.5.6.
        MvcResult result = mockMvc(true).perform(get(AUDITED_PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader("Allow")).isNull();
    }

    @Test
    @DisplayName("BEFORE: a re-spelled POST is rejected by the dispatcher, which echoes the method")
    void withoutTheFilterAReSpelledPostReachesTheDispatcherAndIsEchoedBack() throws Exception {
        // Spring's method matching is case-sensitive, so "pOsT" matches no mapping and
        // DefaultHandlerExceptionResolver answers it. Two things follow that the filter's contract
        // rules out: the request has already traversed every filter behind this one — including
        // TelemetryApiKeyAuthFilter's database lookup — and the rejection reflects the caller's
        // method token instead of the filter's fixed, method-free body.
        MvcResult result = mockMvc(false)
                .perform(request("pOsT", URI.create(AUDITED_PATH))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getErrorMessage())
                .as("the dispatcher's 405 names the method; the filter's body never does")
                .contains("pOsT");
    }

    @Test
    @DisplayName("AFTER: the filter answers it first, with a body that names no method")
    void withTheFilterAReSpelledPostIsRejectedBeforeTheDispatcher() throws Exception {
        MvcResult result = mockMvc(true)
                .perform(request("pOsT", URI.create(AUDITED_PATH))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getErrorMessage())
                .as("no sendError, so this never reached the dispatcher")
                .isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("pOsT");
        assertThat(result.getResponse().getHeader("Allow")).isNull();
    }

    @Test
    @DisplayName("the mapped POST still reaches the handler through the filter")
    void thePostMappingStillWorksThroughTheFilter() throws Exception {
        MvcResult result = mockMvc(true).perform(post(AUDITED_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("ok");
    }

    /** Web MVC and one controller — no datasource, Kafka or Eureka. */
    @Configuration
    @EnableWebMvc
    static class WebMvcOnly {

        @org.springframework.context.annotation.Bean
        SchemesController schemesController() {
            return new SchemesController();
        }
    }

    @RestController
    @RequestMapping("/api/v1/telemetry")
    static class SchemesController {

        @PostMapping("/schemes")
        String schemes() {
            return "ok";
        }
    }
}
