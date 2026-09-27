package org.arghyam.jalsoochak.telemetry.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name resolution seam, so the URL policy can be exercised without touching real DNS.
 */
@FunctionalInterface
public interface HostResolver {

    HostResolver SYSTEM = InetAddress::getAllByName;

    InetAddress[] resolve(String host) throws UnknownHostException;
}
