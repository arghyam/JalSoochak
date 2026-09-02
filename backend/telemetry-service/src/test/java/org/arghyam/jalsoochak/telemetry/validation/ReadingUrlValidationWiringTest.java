package org.arghyam.jalsoochak.telemetry.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.config.MediaFetchRestTemplateConfig;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraint only works if the container can build it — {@link ReadingUrlConstraintValidator}
 * takes its policy through the constructor, which relies on Spring's constraint validator factory.
 * A unit test that instantiates the validator directly would pass even if that wiring were broken,
 * and the failure would then show up as a 500 on live submissions.
 */
@DisplayName("ValidReadingUrl — wired through Bean Validation")
class ReadingUrlValidationWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(MediaFetchRestTemplateConfig.class);

    private static AssamReadingRequest submissionWith(String readingUrl) {
        return AssamReadingRequest.builder()
                .stateSchemeId("SCHEME-1")
                .readingUrl(readingUrl)
                .build();
    }

    @Test
    void rejectsASubmissionPointingAtTheMetadataEndpoint() {
        contextRunner.run(context -> {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<AssamReadingRequest>> violations =
                    validator.validate(submissionWith("http://169.254.169.254/latest/meta-data/"));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("readingUrl");
        });
    }

    @Test
    void acceptsAnOrdinarySubmissionOverEitherScheme() {
        contextRunner.run(context -> {
            Validator validator = context.getBean(Validator.class);

            assertThat(validator.validate(submissionWith("https://adc-ecos.enlightcloud.com/a.jpg"))).isEmpty();
            assertThat(validator.validate(submissionWith("http://adc-ecos.enlightcloud.com/a.jpg"))).isEmpty();
        });
    }

    @Test
    void leavesAManualReadingAlone() {
        contextRunner.run(context -> {
            Validator validator = context.getBean(Validator.class);

            AssamReadingRequest manualReading = AssamReadingRequest.builder()
                    .stateSchemeId("SCHEME-1")
                    .confirmedReading(new BigDecimal("1234"))
                    .build();

            assertThat(validator.validate(manualReading)).isEmpty();
        });
    }
}
