package org.arghyam.jalsoochak.message.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves a host name to its addresses.
 *
 * <p>A seam over {@link InetAddress#getAllByName(String)}, for one reason: the SMTP host check runs
 * on a write path, and a test for "this host is refused because it resolves to a private address"
 * must not depend on what the machine running the build resolves. Production uses
 * {@link DnsHostAddressResolver}; tests supply the addresses directly.
 */
public interface HostAddressResolver {

    /**
     * @return every address the host resolves to; never empty
     * @throws UnknownHostException if the name does not resolve
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
