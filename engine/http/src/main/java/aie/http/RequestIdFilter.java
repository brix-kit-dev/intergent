package aie.http;

import aie.core.Ids;
import aie.core.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>A failure after the answer has started is logged here instead, once, and its connection is cut (code point 34).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "Request-Id";
    static final String LOG_KEY = LogContext.REQUEST_ID;
    static final String PREFIX = "req";

    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Ids.next(PREFIX);
        response.setHeader(HEADER, requestId);
        MDC.put(LOG_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException failure) {
            // Errors and I/O failures, such as a client that went away, are not caught here and stay with Tomcat.
            if (response.isCommitted()) {
                throw cut(request, failure);
            }
            // What Tomcat does with a failure, minus its log line: the error page logs it and answers 500.
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            MDC.remove(LOG_KEY);
        }
    }

    /**
     * An answer that has started cannot become a problem: what went out cannot be taken back, and a problem written after
     * it would garble it. So the failure is logged here, once, with the trace as well, and the connection is cut. Tomcat
     * takes a ClientAbortException for a client that went away, which it neither logs nor sends to the error page, and it
     * closes the connection, so the client learns that the answer broke off. A stream that got no ending event resumes
     * (blueprint 6.4).
     */
    private static IOException cut(HttpServletRequest request, Exception failure) {
        Traceparent.traceId(Collections.list(request.getHeaders(TraceparentFilter.HEADER)))
                .ifPresent(trace -> MDC.put(TraceparentFilter.LOG_KEY, trace));
        try {
            LOG.error("The request failed after its answer had started, so its connection is cut", failure);
        } finally {
            MDC.remove(TraceparentFilter.LOG_KEY);
        }
        return new ClientAbortException(failure);
    }
}
