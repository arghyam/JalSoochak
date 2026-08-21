package org.arghyam.jalsoochak.message.channel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The predicate that decides retry-vs-dead-letter for every outbound notification, so the cases
 * are pinned down here rather than only through the two senders that call it.
 *
 * <p>The asymmetry is the point: a false positive sends a duplicate password reset or a duplicate
 * OTP SMS, a false negative only dead-letters something a human can replay. Anything that cannot be
 * proven to predate transmission must answer false.
 */
class ConnectionFailuresTest {

    @Test
    void connectionRefused_neverReachedTheProvider() {
        assertThat(ConnectionFailures.neverReachedProvider(
                new ConnectException("Connection refused"))).isTrue();
    }

    @Test
    void unknownHost_neverReachedTheProvider() {
        assertThat(ConnectionFailures.neverReachedProvider(
                new UnknownHostException("api.sendgrid.com"))).isTrue();
    }

    @Test
    void noRouteToHost_neverReachedTheProvider() {
        assertThat(ConnectionFailures.neverReachedProvider(
                new NoRouteToHostException("no route"))).isTrue();
    }

    @Test
    void aWrappedConnectException_isFoundThroughTheCauseChain() {
        // Both WebClientRequestException and MailSendException arrive wrapped, often twice over.
        Throwable wrapped = new IllegalStateException("send failed",
                new IOException("transport", new ConnectException("Connection refused")));

        assertThat(ConnectionFailures.neverReachedProvider(wrapped)).isTrue();
    }

    @Test
    void aReadTimeout_isAmbiguous_soNotSafeToReplay() {
        // The request was written; only the response is missing. The provider may have accepted it.
        assertThat(ConnectionFailures.neverReachedProvider(
                new SocketTimeoutException("Read timed out"))).isFalse();
    }

    @Test
    void aConnectionResetMidRequest_isAmbiguous_soNotSafeToReplay() {
        // SocketException, not ConnectException: the socket was open, so bytes may have landed.
        assertThat(ConnectionFailures.neverReachedProvider(
                new SocketException("Connection reset by peer"))).isFalse();
    }

    @Test
    void anUnrecognisedFailure_isAmbiguous_soNotSafeToReplay() {
        assertThat(ConnectionFailures.neverReachedProvider(
                new RuntimeException("something went wrong"))).isFalse();
    }

    @Test
    void nullIsHandled() {
        assertThat(ConnectionFailures.neverReachedProvider(null)).isFalse();
    }

    @Test
    void aCyclicCauseChain_terminates() {
        // A malformed chain must not park the listener thread in an infinite walk.
        SocketException first = new SocketException("reset");
        IOException second = new IOException("transport");
        first.initCause(second);
        second.initCause(first);

        assertThat(ConnectionFailures.neverReachedProvider(first)).isFalse();
    }
}
