package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.IdempotencyKeys;
import aie.core.tenancy.api.IdempotencyKeys.Claim;
import aie.core.tenancy.api.IdempotencyKeys.Key;
import aie.core.tenancy.api.SessionTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Idempotency-Key of blueprint 6.2 on every POST of the public API. The same key with the same request gets the
 * first answer again, whatever it was, so the work runs once; a retry that should run again takes a new key. The same
 * key with a different request is refused. This runs after the key and version checks, so it knows the tenant and the
 * application, and a request those checks refuse leaves the key free. A browser's request is told apart by its end user
 * too, so one end user's key never brings back another's answer.
 *
 * <p>A 429 says that nothing ran and that the caller should come back after Retry-After, so it is not stored and the key
 * is let go: a retry with the same key runs the request.
 *
 * <p>The answer is stored after the controller has given it. A process that dies in between, or a request that ends
 * in an exception no controller turned into an answer, leaves the key claimed until its lease runs out; then a retry
 * runs the request again (see {@link IdempotencyKeys}).
 *
 * <p>An answer that streams cannot be stored as a body, since a retry may come while it is still being written
 * (M2-10-2). The controller hands its message to {@link #STREAMING} before the first event: the key keeps that message,
 * and the stream goes out unbuffered. A retry then sends that message's events again from the first, following the
 * answer while it is written, and runs nothing. A request that asks for a stream is another request than the same one
 * asking for JSON, so the same key on both is refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    /** The contract allows 1 to 255 characters. */
    static final int MAX_KEY = 255;
    /** The fingerprint covers the whole body, so the body is read into memory, and only this much of it. */
    static final int MAX_BODY = 1024 * 1024;
    /** rate_limited: nothing ran, so the answer is not one to replay. */
    static final int TOO_MANY_REQUESTS = 429;
    /**
     * A request attribute a controller sets to the top-level fields of its JSON answer that must not be stored, such as
     * a new key's secret. A replay then leaves them out, so no secret is kept for the 24 hours of a key.
     */
    static final String NOT_STORED = "aie.http.idempotency.not-stored";
    /**
     * A request attribute this filter sets to a {@link Streaming} for each request whose key it took. A controller that
     * streams its answer calls it before the first event.
     */
    static final String STREAMING = "aie.http.idempotency.streaming";
    /**
     * A request attribute this filter sets for a retry of a request whose answer streamed: the ID of that message, whose
     * events the controller sends again, from the first, instead of running the request.
     */
    static final String RESEND = "aie.http.idempotency.resend";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyKeys keys;

    IdempotencyFilter(IdempotencyKeys keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PublicApi.contains(request) || !HttpMethod.POST.matches(request.getMethod()) || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        if (values.size() != 1 || values.getFirst().isEmpty() || values.getFirst().length() > MAX_KEY) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Send one " + HEADER + " of 1 to " + MAX_KEY + " characters");
            return;
        }
        byte[] body = body(request);
        if (body == null) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "The body of a request with an " + HEADER + " may be at most 1 MiB");
            return;
        }
        Owner caller = Objects.requireNonNull((Owner) request.getAttribute(ApiKeyFilter.CALLER), "No caller on a /v1 request");
        Key key = new Key(caller.tenantId(), caller.applicationId(), values.getFirst());
        switch (keys.claim(key, fingerprint(request, body, StreamedRequests.wanted(request), endUser(request)))) {
            case Claim.Acquired acquired -> run(new CachedBodyRequest(request, body), response, chain, key);
            case Claim.InUse inUse -> refuse(request, response, HttpServletResponse.SC_CONFLICT,
                    "idempotency_key_in_use", "A request with this " + HEADER + " is still running");
            case Claim.Reused reused -> refuse(request, response, 422,
                    "idempotency_key_reused", "This " + HEADER + " belongs to a different request");
            case Claim.Finished(int status, String answer) -> replay(response, status, answer);
            case Claim.Streamed(String messageId) -> resend(new CachedBodyRequest(request, body), response, chain, messageId);
        }
    }

    /**
     * What the rest of the chain answers is stored first, then sent; a 429 lets the key go instead. A failure to store
     * still sends it. An answer that streams is kept as its message instead, and goes out as it is written.
     */
    private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Key key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper answer = new ContentCachingResponseWrapper(response);
        AtomicBoolean streamed = new AtomicBoolean();
        request.setAttribute(STREAMING, (Streaming) messageId -> {
            streamed.set(true);
            try {
                keys.finishStreamed(key, messageId);
            } catch (RuntimeException e) {
                LOG.error("The streamed answer to an {} could not be stored, so a retry after the lease runs the request again",
                        HEADER, e);
            }
            return response;
        });
        chain.doFilter(request, answer);
        if (streamed.get()) {
            return;
        }
        try {
            if (answer.getStatus() == TOO_MANY_REQUESTS) {
                keys.release(key);
            } else {
                keys.finish(key, answer.getStatus(), storable(request, answer.getContentAsByteArray()));
            }
        } catch (RuntimeException e) {
            LOG.error("The answer to an {} could not be stored or let go, so a retry after the lease runs the request again",
                    HEADER, e);
        }
        answer.copyBodyToResponse();
    }

    /** The answer as it may be kept. When fields must go and they cannot be taken out, nothing of the body is kept. */
    private static String storable(HttpServletRequest request, byte[] answer) {
        if (!(request.getAttribute(NOT_STORED) instanceof Set<?> fields) || fields.isEmpty()) {
            return new String(answer, StandardCharsets.UTF_8);
        }
        try {
            if (JSON.readTree(answer) instanceof ObjectNode object) {
                fields.forEach(field -> object.remove(String.valueOf(field)));
                return JSON.writeValueAsString(object);
            }
        } catch (JacksonException e) {
            LOG.warn("An answer with fields that must not be stored was not a JSON object, so its body was not kept");
            return "";
        }
        return "";
    }

    /**
     * A retry of a request whose answer streamed: the controller sends that message's events again, from the first, and
     * follows the answer while it is still written. Nothing runs again, nothing is stored, and nothing is buffered.
     */
    private static void resend(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String messageId)
            throws ServletException, IOException {
        request.setAttribute(RESEND, messageId);
        chain.doFilter(request, response);
    }

    /** The first answer again. Only this request's own Request-Id differs. */
    private static void replay(HttpServletResponse response, int status, String answer) throws IOException {
        if (answer.isEmpty() && status >= HttpServletResponse.SC_BAD_REQUEST) {
            // A refusal made with sendError left no body here; the error page writes the problem again.
            response.sendError(status);
            return;
        }
        response.setStatus(status);
        if (!answer.isEmpty()) {
            response.setContentType(status >= HttpServletResponse.SC_BAD_REQUEST
                    ? MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    : MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(answer.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        request.setAttribute(Problems.CODE, code);
        response.sendError(status, detail);
    }

    /** Null when the body is over the limit. A stated length is checked before anything is read. */
    /** What a controller calls to answer an idempotent request as a stream. */
    @FunctionalInterface
    interface Streaming {

        /**
         * Keeps the answer's message with the key, and returns the response to stream it through, unbuffered. A failure to
         * keep it is logged, and the stream goes out all the same.
         */
        HttpServletResponse start(String messageId);
    }

    private static byte @Nullable [] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        return body.length > MAX_BODY ? null : body;
    }

    /** The end user of a request made with a browser's token, or null for one made with a server key. */
    private static @Nullable String endUser(HttpServletRequest request) {
        return request.getAttribute(ApiKeyFilter.BROWSER) instanceof SessionTokens.Session session ? session.endUserId() : null;
    }

    /**
     * The method, the path and the body: the same key on another operation is another request. A request that asks for a
     * stream is another request than the same one asking for JSON (M2-10 decision 1), so its fingerprint says so. A
     * browser's request names its end user too, so another end user of the application with the same key makes another
     * request and never gets this one's answer (M2-12 decision 2). That of any other request is as it was before, so the
     * keys stored before still match.
     */
    private static String fingerprint(HttpServletRequest request, byte[] body, boolean streamed, @Nullable String endUser) {
        String query = request.getQueryString();
        String target = (endUser == null ? "" : "end user " + endUser + "\n") + (streamed ? "stream\n" : "")
                + request.getMethod() + " " + request.getServletPath() + (query == null ? "" : "?" + query) + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(target.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
