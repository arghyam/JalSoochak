package org.arghyam.jalsoochak.telemetry.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address rules behind the media fetch guard. Every literal here is parsed offline —
 * {@link InetAddress#getByName} only touches DNS for a name, never for an address literal.
 */
@DisplayName("SsrfAddressPolicy — which addresses may be dialled")
class SsrfAddressPolicyTest {

    private static InetAddress address(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @ParameterizedTest(name = "{0} is internal")
    @ValueSource(strings = {
            "127.0.0.1",                  // loopback
            "127.1.2.3",
            "0.0.0.0",                    // unspecified
            "0.10.20.30",                 // 0.0.0.0/8
            "169.254.169.254",            // cloud instance metadata
            "169.254.0.1",
            "10.0.0.1",                   // RFC 1918
            "172.16.5.4",
            "172.31.255.255",
            "192.168.1.1",
            "100.64.0.1",                 // carrier-grade NAT
            "100.127.255.255",
            "192.0.0.1",                  // IETF protocol assignments
            "192.0.2.5",                  // TEST-NET-1
            "198.18.0.1",                 // benchmarking
            "198.51.100.7",               // TEST-NET-2
            "203.0.113.9",                // TEST-NET-3
            "224.0.0.1",                  // multicast
            "240.0.0.1",                  // reserved
            "255.255.255.255",            // broadcast
            "::1",                        // IPv6 loopback
            "::",                         // IPv6 unspecified
            "fe80::1",                    // IPv6 link-local
            "fc00::1",                    // IPv6 unique local
            "fd12:3456:789a::1",
            "::ffff:127.0.0.1",           // IPv4-mapped loopback
            "::ffff:169.254.169.254",     // IPv4-mapped metadata endpoint
            "64:ff9b::7f00:1"             // NAT64-wrapped loopback
    })
    void treatsNonPublicAddressesAsInternal(String literal) throws UnknownHostException {
        assertThat(SsrfAddressPolicy.isInternalAddress(address(literal))).isTrue();
    }

    @ParameterizedTest(name = "{0} is public")
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1",
            "104.16.0.1",
            "172.32.0.1",                 // just outside 172.16/12
            "100.63.255.255",             // just below the CGNAT block
            "100.128.0.1",                // just above the CGNAT block
            "192.0.1.1",                  // just outside 192.0.0.0/24
            "198.20.0.1",                 // just outside the benchmarking block
            "203.0.114.9",                // just outside TEST-NET-3
            "2606:4700:4700::1111",
            "2001:4860:4860::8888"
    })
    void allowsOrdinaryPublicAddresses(String literal) throws UnknownHostException {
        assertThat(SsrfAddressPolicy.isInternalAddress(address(literal))).isFalse();
    }

    @Test
    void failsClosedOnAnAbsentAddress() {
        assertThat(SsrfAddressPolicy.isInternalAddress(null)).isTrue();
    }

    @Test
    void blocksInternalAddressesUnderTheDefaultPolicy() throws UnknownHostException {
        SsrfAddressPolicy policy = new SsrfAddressPolicy(false);

        assertThat(policy.isAllowed(address("169.254.169.254"))).isFalse();
        assertThat(policy.isAllowed(address("8.8.8.8"))).isTrue();
    }

    @Test
    void allowsInternalAddressesOnlyWhenExplicitlyOpenedUpForLocalDevelopment() throws UnknownHostException {
        SsrfAddressPolicy policy = new SsrfAddressPolicy(true);

        assertThat(policy.isAllowed(address("127.0.0.1"))).isTrue();
        assertThat(policy.allowsInternalAddresses()).isTrue();
    }
}
