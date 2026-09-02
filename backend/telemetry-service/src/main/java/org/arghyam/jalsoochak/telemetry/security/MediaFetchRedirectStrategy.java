package org.arghyam.jalsoochak.telemetry.security;

import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.net.URI;

/**
 * Applies the media URL policy to every redirect hop.
 *
 * <p>The address guard on the DNS resolver already stops a redirect into private space, so this
 * exists for the checks that live above the IP layer — the host allowlist and the scheme rule.
 * Without it, turning on the allowlist would only constrain the first request, and one 30x from an
 * allowlisted host would step outside it.
 */
public class MediaFetchRedirectStrategy extends DefaultRedirectStrategy {

    private final MediaUrlValidator mediaUrlValidator;

    public MediaFetchRedirectStrategy(MediaUrlValidator mediaUrlValidator) {
        this.mediaUrlValidator = mediaUrlValidator;
    }

    @Override
    public URI getLocationURI(HttpRequest request, HttpResponse response, HttpContext context)
            throws HttpException {
        URI location = super.getLocationURI(request, response, context);
        try {
            mediaUrlValidator.validateTarget(location);
        } catch (MediaUrlNotAllowedException e) {
            throw new ProtocolException("Refusing to follow media redirect: " + e.getReason());
        }
        return location;
    }
}
