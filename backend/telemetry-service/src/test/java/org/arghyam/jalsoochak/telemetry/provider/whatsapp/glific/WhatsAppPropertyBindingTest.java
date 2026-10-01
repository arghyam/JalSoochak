package org.arghyam.jalsoochak.telemetry.provider.whatsapp.glific;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every property the WhatsApp adapters bind to a key that {@code application.yml} declares.
 *
 * <p>A binding whose key the yml does not declare still starts: it falls back to its inline default, so a
 * prefix renamed on one side only fails nothing at build or boot — and several of these defaults point at a
 * real endpoint or flow.</p>
 */
class WhatsAppPropertyBindingTest {

    private static final Pattern PLACEHOLDER_KEY = Pattern.compile("\\$\\{([^:}$]+)");

    @ParameterizedTest
    @ValueSource(classes = {
            GlificContactDirectory.class,
            GlificConversationResumeGateway.class,
            GlificMediaFetcher.class})
    void everyBoundPropertyIsDeclaredInApplicationYaml(Class<?> type) throws IOException {
        Set<String> bound = boundKeys(type);

        assertThat(bound).isNotEmpty();
        assertThat(applicationYamlKeys()).containsAll(bound);
    }

    private static Set<String> boundKeys(Class<?> type) {
        Stream<String> fields = Arrays.stream(type.getDeclaredFields())
                .map(field -> field.getAnnotation(Value.class))
                .filter(Objects::nonNull)
                .map(Value::value);
        Stream<String> constructorParameters = Arrays.stream(type.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(Objects::nonNull)
                .map(Value::value);

        Set<String> keys = new HashSet<>();
        Stream.concat(fields, constructorParameters).forEach(expression -> {
            Matcher matcher = PLACEHOLDER_KEY.matcher(expression);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        });
        return keys;
    }

    private static Set<String> applicationYamlKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            keys.addAll(Arrays.asList(((EnumerablePropertySource<?>) source).getPropertyNames()));
        }
        return keys;
    }
}
