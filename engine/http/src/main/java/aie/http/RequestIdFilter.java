package aie.http;

import aie.core.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The Request-Id of blueprint 6.2. It goes on the response before anything can fail, and into the logs, so a quoted ID
 * leads to that request's log lines. The engine always makes its own: a client's value would be a way to forge log
 * lines or point a support case at someone else's request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "Request-Id";
    static final String LOG_KEY = "request_id";
    private static final String PREFIX = "req";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Ids.next(PREFIX);
        response.setHeader(HEADER, requestId);
        MDC.put(LOG_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LOG_KEY);
        }
    }
}
