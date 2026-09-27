package org.arghyam.jalsoochak.telemetry.security;

import java.net.InetAddress;

/**
 * Decides whether an IP address is safe to open an outbound connection to when the destination was
 * chosen by a caller rather than by configuration.
 *
 * <p>The rules deliberately go beyond the {@link InetAddress} helpers: those cover loopback,
 * link-local, site-local and multicast, but not the unique-local IPv6 range, carrier-grade NAT, or
 * the reserved IPv4 blocks that still route inside a private network. Anything unresolvable or
 * unrecognised is treated as internal, so the policy fails closed.
 *
 * <p>{@code allowInternalAddresses} exists for local development, where the media host genuinely is
 * on loopback. It must stay {@code false} in every deployed environment.
 */
public class SsrfAddressPolicy {

    private final boolean allowInternalAddresses;

    public SsrfAddressPolicy(boolean allowInternalAddresses) {
        this.allowInternalAddresses = allowInternalAddresses;
    }

    public boolean isAllowed(InetAddress address) {
        return allowInternalAddresses || !isInternalAddress(address);
    }

    public boolean allowsInternalAddresses() {
        return allowInternalAddresses;
    }

    public static boolean isInternalAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()          // 0.0.0.0, ::
                || address.isLoopbackAddress()   // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (cloud metadata), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16, fec0::/10
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        return octets.length == 4 ? isInternalIpv4(octets) : isInternalIpv6(octets);
    }

    private static boolean isInternalIpv4(byte[] octets) {
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        int third = octets[2] & 0xFF;

        if (first == 0) {
            return true;                                          // 0.0.0.0/8 "this network"
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;                                          // 100.64.0.0/10 carrier-grade NAT
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return true;                                          // 192.0.0.0/24, 192.0.2.0/24
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true;                                          // 198.18.0.0/15 benchmarking
        }
        if (first == 192 && second == 88 && third == 99) {
            return true;                                          // 192.88.99.0/24 6to4 relay anycast
        }
        if (first == 198 && second == 51 && third == 100) {
            return true;                                          // 198.51.100.0/24 documentation
        }
        if (first == 203 && second == 0 && third == 113) {
            return true;                                          // 203.0.113.0/24 documentation
        }
        return first >= 240;                                      // 240.0.0.0/4 reserved, incl. broadcast
    }

    private static boolean isInternalIpv6(byte[] octets) {
        if ((octets[0] & 0xFE) == 0xFC) {
            return true;                                          // fc00::/7 unique local
        }
        // The JVM normally hands back an Inet4Address for ::ffff:a.b.c.d, but a raw 16-byte form can
        // still reach here, and 6to4 and Teredo carry an IPv4 destination in an address the JVM has
        // no reason to unwrap at all. Judge the embedded IPv4 address on the IPv4 rules rather than
        // letting the wrapper carry it past them.
        byte[] embeddedIpv4 = embeddedIpv4(octets);
        if (embeddedIpv4 != null) {
            try {
                return isInternalAddress(InetAddress.getByAddress(embeddedIpv4));
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the IPv4 address embedded in an IPv4-mapped ({@code ::ffff:a.b.c.d}), IPv4-compatible
     * ({@code ::a.b.c.d}), NAT64 ({@code 64:ff9b::a.b.c.d}), 6to4 ({@code 2002:a.b.c.d::/48}) or
     * Teredo ({@code 2001:0::/32}) address, or {@code null} when the address carries none.
     */
    private static byte[] embeddedIpv4(byte[] octets) {
        // 6to4 puts the IPv4 endpoint straight after the 2002::/16 prefix, so 2002:7f00:1:: is
        // 127.0.0.1 in an IPv6 costume and would otherwise pass every check above.
        boolean sixToFour = (octets[0] & 0xFF) == 0x20 && (octets[1] & 0xFF) == 0x02;
        if (sixToFour) {
            return new byte[]{octets[2], octets[3], octets[4], octets[5]};
        }
        // Teredo carries the client's IPv4 in the last four octets, stored bit-flipped.
        boolean teredo = (octets[0] & 0xFF) == 0x20 && (octets[1] & 0xFF) == 0x01
                && (octets[2] & 0xFF) == 0x00 && (octets[3] & 0xFF) == 0x00;
        if (teredo) {
            return new byte[]{
                    (byte) ~octets[12], (byte) ~octets[13], (byte) ~octets[14], (byte) ~octets[15]};
        }
        boolean nat64 = (octets[0] & 0xFF) == 0x00 && (octets[1] & 0xFF) == 0x64
                && (octets[2] & 0xFF) == 0xFF && (octets[3] & 0xFF) == 0x9B;
        if (nat64) {
            return new byte[]{octets[12], octets[13], octets[14], octets[15]};
        }
        for (int i = 0; i < 10; i++) {
            if (octets[i] != 0) {
                return null;
            }
        }
        boolean mapped = (octets[10] & 0xFF) == 0xFF && (octets[11] & 0xFF) == 0xFF;
        boolean compatible = octets[10] == 0 && octets[11] == 0;
        if (!mapped && !compatible) {
            return null;
        }
        return new byte[]{octets[12], octets[13], octets[14], octets[15]};
    }
}
