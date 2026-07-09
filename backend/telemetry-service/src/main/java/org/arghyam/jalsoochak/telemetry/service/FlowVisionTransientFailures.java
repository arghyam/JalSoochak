package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

public final class FlowVisionTransientFailures {

    private static final Class<? extends Throwable>[] RETRIABLE_EXCEPTIONS = buildRetriableExceptions();

    private FlowVisionTransientFailures() {
    }

    public static boolean isServiceUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CallNotPermittedException || current instanceof BulkheadFullException) {
                return true;
            }
            for (Class<? extends Throwable> retriableException : RETRIABLE_EXCEPTIONS) {
                if (retriableException.isInstance(current)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static Class<? extends Throwable>[] retriableExceptions() {
        return RETRIABLE_EXCEPTIONS.clone();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable>[] buildRetriableExceptions() {
        return new Class[]{
                ResourceAccessException.class,
                HttpServerErrorException.BadGateway.class,
                HttpServerErrorException.ServiceUnavailable.class,
                HttpServerErrorException.GatewayTimeout.class,
                HttpClientErrorException.TooManyRequests.class
        };
    }
}
