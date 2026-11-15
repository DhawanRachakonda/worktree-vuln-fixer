package dev.intel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires {@code Authorization: Bearer <token>} in front of the MCP endpoint and the operational (actuator) endpoints.
 * A request without the right token is answered with 401 before any MCP or tool code runs, so no record data can leave.
 * The token is compared in constant time (both sides are hashed first so that their lengths do not matter) and never
 * appears in logs.
 *
 * <p>The two orchestrator probes stay open: they answer only {@code UP} or {@code DOWN} and carry no details, and a
 * container platform has no secret to send.
 */
public final class BearerTokenFilter extends OncePerRequestFilter {

    /** Probe paths that never need the token. Matched exactly, so no path trick can reach another endpoint. */
    static final Set<String> OPEN_PATHS = Set.of("/actuator/health/liveness", "/actuator/health/readiness");

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String SCHEME = "Bearer ";

    private final byte[] expected;

    public BearerTokenFilter(String token) {
        this.expected = sha256(token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return OPEN_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorized(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("Rejected {} {} from {}: missing or wrong bearer token", request.getMethod(),
                request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    boolean authorized(String header) {
        if (header == null || header.length() <= SCHEME.length() || !header.regionMatches(true, 0, SCHEME, 0,
                SCHEME.length())) {
            return false;
        }
        String presented = header.substring(SCHEME.length()).strip();
        return MessageDigest.isEqual(expected, sha256(presented));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
