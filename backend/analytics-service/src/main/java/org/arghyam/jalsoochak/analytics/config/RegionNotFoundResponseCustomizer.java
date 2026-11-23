package org.arghyam.jalsoochak.analytics.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Documents the phased-rollout guard's 404 on every operation that takes one of the region params
 * {@link RegionOnboardingInterceptor} checks. Read off the guard's own list, so a new endpoint is
 * documented as soon as it takes one. An operation that documents its own 404 keeps it.
 *
 * <p>Registered whatever the switch says, so that every deployment publishes the same contract; the
 * description says when the 404 applies.
 */
public class RegionNotFoundResponseCustomizer implements OperationCustomizer {

    private static final String NOT_FOUND = "404";

    private static final String DESCRIPTION =
            "Only with phased rollout on (PHASED_ROLLOUT_ENABLED): a region the request names isn't onboarded";

    private static final Set<String> REGION_PARAM_NAMES = RegionOnboardingInterceptor.REGION_PARAMS.stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    private static final JsonNode EXAMPLE = parse(SwaggerExamples.ERROR_REGION_NOT_FOUND);

    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (!takesARegionParam(handlerMethod)) {
            return operation;
        }
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        if (!operation.getResponses().containsKey(NOT_FOUND)) {
            operation.getResponses().addApiResponse(NOT_FOUND, notFoundResponse());
        }
        return operation;
    }

    private static boolean takesARegionParam(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .map(RegionNotFoundResponseCustomizer::requestParamName)
                .anyMatch(REGION_PARAM_NAMES::contains);
    }

    /** The name Spring binds the parameter by, or null when it isn't a {@code @RequestParam}. */
    private static String requestParamName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam == null) {
            return null;
        }
        if (!requestParam.name().isBlank()) {
            return requestParam.name();
        }
        // Copied so the handler's own parameter is left as Spring built it.
        MethodParameter copy = new MethodParameter(parameter);
        copy.initParameterNameDiscovery(PARAMETER_NAMES);
        return copy.getParameterName();
    }

    /** A new instance per operation: the models are mutable, and a later customizer may edit one. */
    private static ApiResponse notFoundResponse() {
        return new ApiResponse()
                .description(DESCRIPTION)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().addExamples("failure", new Example().value(EXAMPLE))));
    }

    private static JsonNode parse(String json) {
        try {
            return Json.mapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
