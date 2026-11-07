package aie.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The version header of blueprint 6.2. There is one version so far, so this refuses any other: answering a caller in a
 * version it did not ask for would break it quietly. Without the header, the version locked on the application applies,
 * and with only one version nothing needs converting yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class EngineVersionFilter extends OncePerRequestFilter {

    static final String CURRENT = "2026-11-05";
    private static final String HEADER = "Engine-Version";
    private static final String PUBLIC_API = "/v1";

    /** The servlet path is decoded, so an escaped path cannot slip past this check. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals(PUBLIC_API) && !path.startsWith(PUBLIC_API + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> versions = Collections.list(request.getHeaders(HEADER));
        if (versions.isEmpty() || versions.equals(List.of(CURRENT))) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, HEADER + " must be " + CURRENT);
    }
}
