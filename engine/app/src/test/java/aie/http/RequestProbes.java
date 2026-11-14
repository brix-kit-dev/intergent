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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only stand-ins for the code that later points add behind RequestIdFilter. */
public final class RequestProbes {

    private static final Logger LOG = LoggerFactory.getLogger(RequestProbes.class);

    /** How often the /v1/test/run routes really ran, so a test can tell a replay from a second run. */
    public static final AtomicInteger RUNS = new AtomicInteger();
    /** How many more times /v1/test/run-when-free turns a request away with 429. */
    public static final AtomicInteger BUSY = new AtomicInteger();
    /** Holds /v1/test/run-slowly inside its controller until a test counts it down. */
    public static volatile CountDownLatch hold = new CountDownLatch(0);

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
                case "/test/filter-fails-checked" -> throw new ServletException("a checked failure inside a filter");
                case "/test/filter-fails-late" -> {
                    response.getWriter().write("partial");
                    response.flushBuffer();
                    throw new IllegalStateException("a failure after the answer started");
                }
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

        /** Work that must run once per Idempotency-Key. The body only changes the fingerprint. */
        @PostMapping("/v1/test/run")
        ResponseEntity<Map<String, Integer>> run(@RequestBody(required = false) Map<String, Object> body) {
            return ResponseEntity.status(201).body(Map.of("run", RUNS.incrementAndGet()));
        }

        /** Shows what the controller got, after the idempotency filter has read the body for its fingerprint. */
        @PostMapping("/v1/test/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }

        /** An answer with a secret, the way creating a key gives one: the stored copy must leave it out. */
        @PostMapping("/v1/test/secret")
        Map<String, Object> secret(HttpServletRequest request) {
            int run = RUNS.incrementAndGet();
            request.setAttribute(IdempotencyFilter.NOT_STORED, Set.of("secret"));
            return Map.of("run", run, "secret", "s3cret-" + run);
        }

        /** A secret in an answer that is not a JSON object, so the fields cannot be taken out and nothing is kept. */
        @PostMapping(path = "/v1/test/secret-text", produces = "text/plain")
        String secretText(HttpServletRequest request) {
            int run = RUNS.incrementAndGet();
            request.setAttribute(IdempotencyFilter.NOT_STORED, Set.of("secret"));
            return "s3cret-" + run;
        }

        /** Turned away with 429 while BUSY counts down, as a full instance turns an ask away; then the work runs. */
        @PostMapping("/v1/test/run-when-free")
        ResponseEntity<Object> runWhenFree() {
            int run = RUNS.incrementAndGet();
            if (BUSY.getAndDecrement() > 0) {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.RETRY_AFTER, "5");
                return Problems.response(429, "rate_limited", "Too much at once, so try again later", headers);
            }
            return ResponseEntity.status(201).body(Map.of("run", run));
        }

        @PostMapping("/v1/test/run-and-fail")
        Map<String, Integer> runAndFail() {
            RUNS.incrementAndGet();
            throw new IllegalStateException("a failure after the work");
        }

        @PostMapping("/v1/test/run-slowly")
        Map<String, Integer> runSlowly() throws InterruptedException {
            int run = RUNS.incrementAndGet();
            hold.await(30, TimeUnit.SECONDS);
            return Map.of("run", run);
        }

        @GetMapping("/test/param")
        Map<String, String> param(@RequestParam("needed") String needed) {
            return Map.of("needed", needed);
        }
    }
}
