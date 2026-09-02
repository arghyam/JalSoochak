package org.arghyam.jalsoochak.telemetry.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.security.HostResolver;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.security.SsrfAddressPolicy;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.net.InetAddress;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bean Validation support for tests that validate {@code AssamReadingRequest} outside a Spring
 * context.
 *
 * <p>{@link ReadingUrlConstraintValidator} takes its policy through the constructor — in production
 * Spring's constraint validator factory supplies it — so the stock
 * {@code Validation.buildDefaultValidatorFactory()} cannot build it. This supplies the same wiring
 * with a resolver that never touches the network.
 */
public final class ReadingUrlTestValidation {

    private static final Pattern IP_LITERAL =
            Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*)$");

    private ReadingUrlTestValidation() {
    }

    /**
     * Resolves names to a fixed public address so tests turn on policy rather than on real DNS,
     * while leaving address literals to resolve to themselves exactly as the system resolver does —
     * otherwise the fixture would hide the very case the guard exists for.
     */
    public static HostResolver publicNameResolver() {
        return host -> IP_LITERAL.matcher(host).matches()
                ? InetAddress.getAllByName(host)
                : new InetAddress[]{InetAddress.getByName("8.8.8.8")};
    }

    /** The policy as production configures it: http and https, any public host, no internal addresses. */
    public static MediaUrlValidator policy() {
        return policy(Set.of());
    }

    public static MediaUrlValidator policy(Set<String> allowedHosts) {
        return new MediaUrlValidator(true, false, allowedHosts,
                new SsrfAddressPolicy(false), publicNameResolver());
    }

    /** A validator that builds the reading-url constraint the way the container does. */
    public static Validator validator() {
        return Validation.byDefaultProvider()
                .configure()
                .constraintValidatorFactory(new PolicyAwareConstraintValidatorFactory())
                .buildValidatorFactory()
                .getValidator();
    }

    /**
     * The same validator for MockMvc {@code standaloneSetup}, whose default validator cannot build
     * the constraint either: {@code standaloneSetup(controller).setValidator(springValidator())}.
     */
    public static SpringValidatorAdapter springValidator() {
        return new SpringValidatorAdapter(validator());
    }

    private static class PolicyAwareConstraintValidatorFactory implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            if (key == ReadingUrlConstraintValidator.class) {
                return key.cast(new ReadingUrlConstraintValidator(policy(), true));
            }
            try {
                return key.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new ValidationException("Unable to instantiate " + key.getName(), e);
            }
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // Nothing pooled.
        }
    }
}
