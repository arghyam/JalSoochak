package org.arghyam.jalsoochak.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/**
 * Guards the {@code org.webjars:swagger-ui} pin added for the security audit finding
 * "Outdated DOMPurify Dependency with Known Vulnerabilities" (CWE-1104), reported against
 * {@code /webjars/swagger-ui/swagger-ui-bundle.js}.
 *
 * <p>springdoc-openapi 2.5.0 pulls swagger-ui 5.13.0, which bundles DOMPurify 3.0.11 — 20 known
 * advisories, including CVE-2024-45801 and CVE-2024-47875 (HIGH, fixed in 3.1.3) and
 * CVE-2025-26791 (fixed in 3.2.4). The webjar is therefore pinned in {@code pom.xml} via
 * {@code <swagger-ui.version>}.
 *
 * <p>That pin is only half the fix. springdoc 2.5.0 ships a {@code springdoc.config.properties}
 * containing {@code springdoc.swagger-ui.version=5.13.0}, and
 * {@code AbstractSwaggerResourceResolver} uses that value to build the versioned webjar resource
 * path. No webjars-locator is on the classpath, so it is the only thing mapping the version-less
 * URL onto the versioned directory inside the jar. If {@code application.yml} and {@code pom.xml}
 * ever disagree, every Swagger UI asset 404s at runtime and the page renders blank —
 * {@link #webjarVersionMatchesConfiguredVersion()} is the guard against exactly that.
 */
class SwaggerUiWebjarVersionTest {

    /** Highest "fixed in" across the advisories affecting the previously shipped 3.0.11. */
    private static final int[] MIN_DOMPURIFY = {3, 2, 4};

    private static final Pattern BUNDLE_PATH =
            Pattern.compile("/META-INF/resources/webjars/swagger-ui/([^/]+)/swagger-ui-bundle\\.js$");

    /** Matches the minified tail of {@code DOMPurify.version = "x.y.z"}. */
    private static final Pattern DOMPURIFY_VERSION =
            Pattern.compile("=\"(\\d+)\\.(\\d+)\\.(\\d+)\",DOMPurify\\.removed");

    @Test
    void exactlyOneSwaggerUiWebjarOnClasspath() throws IOException {
        assertThat(bundleResources())
                .as("a second swagger-ui webjar would make the served version non-deterministic")
                .hasSize(1);
    }

    @Test
    void webjarVersionMatchesConfiguredVersion() throws IOException {
        String configured = configuredSwaggerUiVersion();

        assertThat(configured)
                .as("springdoc.swagger-ui.version must be set in application.yml; without it "
                        + "springdoc falls back to its built-in 5.13.0 and every asset 404s")
                .isNotNull();
        assertThat(webjarVersion())
                .as("pom.xml <swagger-ui.version> and springdoc.swagger-ui.version have drifted")
                .isEqualTo(configured);
    }

    @Test
    void bundledDomPurifyIsNotVulnerable() throws IOException {
        String bundle = read(bundleResources()[0]);
        Matcher matcher = DOMPURIFY_VERSION.matcher(bundle);

        assertThat(matcher.find())
                .as("no DOMPurify version marker in swagger-ui-bundle.js — the marker's shape "
                        + "changed and this guard needs updating, not deleting")
                .isTrue();

        int[] actual = {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        };

        assertThat(isAtLeastMinimum(actual))
                .as("bundled DOMPurify %d.%d.%d is below the %d.%d.%d floor required by the audit "
                        + "finding (CVE-2024-45801, CVE-2024-47875, CVE-2025-26791)",
                        actual[0], actual[1], actual[2],
                        MIN_DOMPURIFY[0], MIN_DOMPURIFY[1], MIN_DOMPURIFY[2])
                .isTrue();
    }

    @Test
    void swaggerInitializerStillMatchesSpringdocTransformerAnchors() throws IOException {
        Resource[] initializers = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/swagger-ui/*/swagger-initializer.js");
        assertThat(initializers).hasSize(1);

        // springdoc's AbstractSwaggerIndexTransformer rewrites swagger-initializer.js by splicing
        // around these two literals. If a swagger-ui upgrade changes them, springdoc silently stops
        // injecting configUrl and the UI loads the petstore demo spec instead of ours.
        assertThat(read(initializers[0]))
                .contains("presets: [")
                .contains("layout: \"StandaloneLayout\"");
    }

    private static boolean isAtLeastMinimum(int[] actual) {
        for (int i = 0; i < MIN_DOMPURIFY.length; i++) {
            if (actual[i] != MIN_DOMPURIFY[i]) {
                return actual[i] > MIN_DOMPURIFY[i];
            }
        }
        return true;
    }

    private static Resource[] bundleResources() throws IOException {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/swagger-ui/*/swagger-ui-bundle.js");
    }

    private static String webjarVersion() throws IOException {
        Matcher matcher = BUNDLE_PATH.matcher(bundleResources()[0].getURL().getPath());
        assertThat(matcher.find()).as("unexpected webjar layout").isTrue();
        return matcher.group(1);
    }

    private static String configuredSwaggerUiVersion() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        return properties == null ? null : properties.getProperty("springdoc.swagger-ui.version");
    }

    private static String read(Resource resource) throws IOException {
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
