package aie.http;

import aie.core.tenancy.api.ApiVersion;
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
 * and with only one version nothing needs converting yet. It runs after {@link ApiKeyFilter}, which finds that
 * application.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class EngineVersionFilter extends OncePerRequestFilter {

    static final String CURRENT = ApiVersion.CURRENT;
    private static final String HEADER = "Engine-Version";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PublicApi.contains(request);
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
