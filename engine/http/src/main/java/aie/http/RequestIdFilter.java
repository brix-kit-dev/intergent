package aie.http;

import aie.core.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
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
 *
 * <p>A failure that a filter or the servlet throws goes from here to the error page, which logs it with this ID. Left to
 * Tomcat, it would also be logged once more without the ID, so every failure would count twice.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "Request-Id";
    static final String LOG_KEY = "request_id";
    static final String PREFIX = "req";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Ids.next(PREFIX);
        response.setHeader(HEADER, requestId);
        MDC.put(LOG_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException failure) {
            // An answer that has started cannot become a problem any more, so Tomcat handles that failure as before.
            // Errors and I/O failures, such as a client that went away, are not caught here and stay with Tomcat too.
            if (response.isCommitted()) {
                throw failure;
            }
            // What Tomcat does with a failure, minus its log line: the error page logs it and answers 500.
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            MDC.remove(LOG_KEY);
        }
    }
}
