package org.arghyam.jalsoochak.message.util;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Decides whether a URL can be fetched by a third party on the public internet.
 *
 * <p>Glific hands every media URL to Meta, which downloads it from its own network. A URL that
 * resolves perfectly from inside our cluster — a container name, a service IP, an RFC 1918 address —
 * is refused there by a destination filter, and the officer receives a WhatsApp document that will
 * not open:</p>
 *
 * <pre>
 * (#131053) Media upload error, Downloading media from webhook failed with http code 403,
 *           status message Forbidden. Your destination may have been blocked by a destination filter.
 * </pre>
 *
 * <p>The check is deliberately <em>lexical</em>: no DNS lookup, no network call, so it is safe to run
 * during bean startup and gives the same verdict on every host. It therefore catches the mistake that
 * actually happens — an internal address configured as the public one — rather than proving public
 * reachability, which only a fetch from outside can do.</p>
 */
public final class PublicUrlValidator {

    private PublicUrlValidator() {
    }

    /** Hostname suffixes that only resolve inside a private network. */
    private static final String[] PRIVATE_HOST_SUFFIXES = {
            ".local", ".localdomain", ".internal", ".intranet", ".lan", ".home", ".corp",
            ".svc", ".svc.cluster.local", ".cluster.local"
    };

    /**
     * Explains why {@code url} cannot be fetched from the public internet, or returns {@code null}
     * when it looks publicly reachable.
     *
     * <p>A {@code null} return is not a guarantee of reachability — a public hostname can still be
     * firewalled, or the object can still be private. It only means the URL is not disqualified by
     * its own shape.</p>
     *
     * @param url the absolute URL that will be handed to an external service
     * @return a short human-readable reason, or {@code null} if the URL looks publicly reachable
     */
    public static String unreachableReason(String url) {
        if (url == null || url.isBlank()) {
            return "URL is blank";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "not a valid URL";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "scheme must be http or https";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL has no host — it must be absolute";
        }
        // A fully-qualified name may carry the DNS root dot ("localhost.", "minio.svc.cluster.local.").
        // It resolves exactly like the dot-less form, so drop it before classifying — otherwise the
        // suffix list misses it and the trailing dot alone satisfies the multi-label check below.
        String lowerHost = stripRootDot(host.toLowerCase());

        if (isPrivateIpLiteral(lowerHost)) {
            return "host " + host + " is a private or loopback address, unreachable from the public internet";
        }
        for (String suffix : PRIVATE_HOST_SUFFIXES) {
            if (lowerHost.endsWith(suffix)) {
                return "host " + host + " uses the private suffix '" + suffix + "'";
            }
        }
        // A single-label host ("minio", "localhost") is a container or LAN name, never public DNS. Only
        // DNS names are labelled — an IPv6 literal carries no dots, and a public one is fine to fetch.
        if (!isIpv6Literal(lowerHost) && !lowerHost.contains(".")) {
            return "host " + host + " is a single-label name, resolvable only inside the local network";
        }
        return null;
    }

    /** Convenience form of {@link #unreachableReason(String)} for call sites that only need the verdict. */
    public static boolean isPubliclyReachable(String url) {
        return unreachableReason(url) == null;
    }

    /**
     * Removes a single trailing DNS root dot. Only one: {@code "host.."} is not a valid name, so it is
     * left alone to be judged on its own shape rather than normalised into a valid-looking one.
     */
    private static String stripRootDot(String host) {
        return host.length() > 1 && host.endsWith(".") && !host.endsWith("..")
                ? host.substring(0, host.length() - 1)
                : host;
    }

    /** True for a bracketed IPv6 literal, the only form {@link URI#getHost()} returns for IPv6. */
    private static boolean isIpv6Literal(String host) {
        return host.startsWith("[") && host.endsWith("]");
    }

    /**
     * Recognises loopback, link-local, carrier-NAT and RFC 1918 literals, plus IPv6 loopback and the
     * unique-local {@code fc00::/7} block. Hostnames are not resolved, so an internal name that maps
     * to a private address is caught by the suffix and single-label rules above instead.
     */
    private static boolean isPrivateIpLiteral(String host) {
        if (host.equals("localhost") || host.equals("::1") || host.equals("[::1]") || host.equals("0.0.0.0")) {
            return true;
        }
        // IPv6 unique-local (fc00::/7) and link-local (fe80::/10), with or without brackets.
        String v6 = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (v6.contains(":") && (v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe8")
                || v6.startsWith("fe9") || v6.startsWith("fea") || v6.startsWith("feb"))) {
            return true;
        }
        int[] octets = parseIpv4(host);
        if (octets == null) {
            return false;
        }
        int a = octets[0];
        int b = octets[1];
        return a == 10                                   // 10.0.0.0/8
                || a == 127                              // 127.0.0.0/8 loopback
                || (a == 192 && b == 168)                // 192.168.0.0/16
                || (a == 172 && b >= 16 && b <= 31)      // 172.16.0.0/12
                || (a == 169 && b == 254)                // 169.254.0.0/16 link-local
                || (a == 100 && b >= 64 && b <= 127);    // 100.64.0.0/10 carrier NAT
    }

    /** Returns the four octets of a dotted-quad literal, or {@code null} if {@code host} is not one. */
    private static int[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return null;
            }
            for (int j = 0; j < parts[i].length(); j++) {
                if (!Character.isDigit(parts[i].charAt(j))) {
                    return null;
                }
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }
}
