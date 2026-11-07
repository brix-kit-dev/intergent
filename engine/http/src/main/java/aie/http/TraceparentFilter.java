package aie.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the caller's trace ID into the logs, so the logs of the host and the engine line up (blueprint 13.1). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class TraceparentFilter extends OncePerRequestFilter {

    static final String HEADER = "traceparent";
    static final String LOG_KEY = "trace_id";

    /** The failures that reach the error page are the ones most worth tracing, so this filter runs for them too. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Traceparent.traceId(Collections.list(request.getHeaders(HEADER))).ifPresent(trace -> MDC.put(LOG_KEY, trace));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LOG_KEY);
        }
    }
}
