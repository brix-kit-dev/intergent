package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
            switch (((HttpServletRequest) request).getRequestURI()) {
                case "/test/filter-log" -> {
                    LOG.info("a line from inside a filter");
                    chain.doFilter(request, response);
                }
                case "/test/refused-by-filter" -> ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                case "/test/send-error" -> ((HttpServletResponse) response).sendError(HttpServletResponse.SC_BAD_REQUEST);
                case "/test/send-error-500" -> ((HttpServletResponse) response).sendError(
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "an internal message");
                case "/test/filter-fails" -> throw new IllegalStateException("a failure inside a filter");
                default -> chain.doFilter(request, response);
            }
        }
    }

    static class Unwritable {

        public String getValue() {
            throw new IllegalStateException("a failure while writing a response");
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

        /** Spring itself fails here, while writing the response. */
        @GetMapping("/test/unwritable")
        Unwritable unwritable() {
            return new Unwritable();
        }

        @PostMapping("/test/body")
        Map<String, String> body(@RequestBody Map<String, String> body) {
            return body;
        }

        @GetMapping("/test/number/{number}")
        Map<String, Long> number(@PathVariable("number") long number) {
            return Map.of("number", number);
        }

        /** What a /v1 handler reads to learn who is calling. */
        @GetMapping("/v1/test/caller")
        Map<String, String> caller(HttpServletRequest request) {
            Owner owner = (Owner) request.getAttribute(ApiKeyFilter.CALLER);
            return Map.of("keyId", owner.keyId(), "tenantId", owner.tenantId(), "applicationId", owner.applicationId());
        }

        @GetMapping("/test/param")
        Map<String, String> param(@RequestParam("needed") String needed) {
            return Map.of("needed", needed);
        }
    }
}
