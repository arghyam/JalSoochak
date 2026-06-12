package org.arghyam.jalsoochak.user.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class ForbiddenAccessExceptionTest {

    @Test
    void testConstructorWithMessage() {
        String message = "Access forbidden";
        ForbiddenAccessException exception = new ForbiddenAccessException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testExceptionTypeHierarchy() {
        ForbiddenAccessException exception = new ForbiddenAccessException("Test message");

        assertAll("Type hierarchy checks",
            () -> assertTrue(exception instanceof RuntimeException, "Should be RuntimeException"),
            () -> assertTrue(exception instanceof Exception, "Should be Exception"),
            () -> assertTrue(exception instanceof Throwable, "Should be Throwable")
        );
    }

    @Test
    void testExceptionWithNullMessage() {
        ForbiddenAccessException exception = new ForbiddenAccessException(null);

        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testExceptionWithEmptyMessage() {
        String message = "";
        ForbiddenAccessException exception = new ForbiddenAccessException(message);

        assertEquals(message, exception.getMessage());
        assertTrue(exception.getMessage().isEmpty());
    }

    @Test
    void testExceptionWithLongMessage() {
        String message = "This is a very long error message that describes in detail why the access was forbidden and provides context for the user";
        ForbiddenAccessException exception = new ForbiddenAccessException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testExceptionStackTrace() {
        ForbiddenAccessException exception = new ForbiddenAccessException("Test message");

        StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull(stackTrace);
        assertTrue(stackTrace.length > 0);
    }

    @Test
    void testExceptionMessageConsistency() {
        String message = "Consistent message";
        ForbiddenAccessException exception1 = new ForbiddenAccessException(message);
        ForbiddenAccessException exception2 = new ForbiddenAccessException(message);

        assertEquals(exception1.getMessage(), exception2.getMessage());
        assertEquals(message, exception1.getMessage());
        assertEquals(message, exception2.getMessage());
    }
}
