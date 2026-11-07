package aie.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only stand-ins for the code that later points add behind RequestIdFilter. */
final class RequestProbes {

    private static final Logger LOG = LoggerFactory.getLogger(RequestProbes.class);

    private RequestProbes() {
    }

    /** An early refusal, the way authentication will answer before any controller runs. */
    @Component
    @Order(0)
    static class RefusingFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (((HttpServletRequest) request).getRequestURI().equals("/test/refused-by-filter")) {
                ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    @RestController
    static class Routes {

        @GetMapping("/test/log")
        Map<String, String> log() {
            LOG.info("a line from inside a request");
            return Map.of("logged", "yes");
        }

        @GetMapping("/test/fail")
        Map<String, String> fail() {
            throw new IllegalStateException("a failure inside a request");
        }
    }
}
