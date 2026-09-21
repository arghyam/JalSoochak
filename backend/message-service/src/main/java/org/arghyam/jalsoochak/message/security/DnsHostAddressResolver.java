package org.arghyam.jalsoochak.message.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

/**
 * The production {@link HostAddressResolver}: plain JVM DNS resolution.
 *
 * <p>Every address a name resolves to is returned, and the caller must judge all of them. A host
 * that resolves to one public address and one private one is a standard DNS-rebinding shape, and
 * accepting it because the first address passed would be the whole hole.
 */
@Component
public class DnsHostAddressResolver implements HostAddressResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
