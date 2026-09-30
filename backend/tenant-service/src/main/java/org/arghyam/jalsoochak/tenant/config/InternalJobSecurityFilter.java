package org.arghyam.jalsoochak.tenant.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public class InternalJobSecurityFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final byte[] expectedToken;
    private final String internalPathPrefix;

    public InternalJobSecurityFilter(String expectedToken, String internalPathPrefix) {
        this.expectedToken = expectedToken == null ? new byte[0] : expectedToken.getBytes(StandardCharsets.UTF_8);
        this.internalPathPrefix = internalPathPrefix;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(internalPathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedToken = request.getHeader(TOKEN_HEADER);
        if (expectedToken.length == 0 || suppliedToken == null
                || !MessageDigest.isEqual(expectedToken, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}