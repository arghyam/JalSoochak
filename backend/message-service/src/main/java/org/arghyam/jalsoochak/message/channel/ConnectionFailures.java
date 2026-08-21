package org.arghyam.jalsoochak.message.channel;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.UnknownHostException;

/**
 * Decides whether a transport failure happened <em>before</em> the request reached the provider.
 *
 * <p>This is the one question that separates a safe retry from a duplicate delivery. If the socket
 * never opened, nothing was transmitted and a replay costs nothing; anything later — a reset while
 * the body was in flight, a read timeout waiting for the response, an SMTP failure after DATA — is
 * ambiguous, because the provider may already have accepted the message and only the acknowledgement
 * was lost. Both channels here are single-shot notifications with no idempotency key, so an
 * ambiguous outcome is treated as permanent: a password reset the user can request again beats two
 * password resets, and a duplicate OTP SMS costs money and can confuse the login flow.
 *
 * <p>Deliberately checks {@code java.net} types rather than the transport-specific wrappers
 * ({@code WebClientRequestException}, {@code MailConnectException}): those wrappers cover both
 * pre- and post-transmission failures, and the JavaMail one lives in a shaded implementation
 * package that changes between Angus and the old Sun provider.
 */
final class ConnectionFailures {

    private ConnectionFailures() {
    }

    /**
     * Bounded so a cyclic cause chain cannot spin forever. Real chains here are two or three deep;
     * a transport that re-wraps its own cause is a bug, not a reason to hang the listener thread.
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * True when the cause chain shows the connection was never established, so no byte of the
     * request reached the provider.
     *
     * <p>Everything else — including a connection reset mid-request and any read timeout — returns
     * false, because delivery cannot be ruled out.
     */
    static boolean neverReachedProvider(Throwable t) {
        Throwable cause = t;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException
                    || cause instanceof PortUnreachableException) {
                return true;
            }
        }
        return false;
    }
}
