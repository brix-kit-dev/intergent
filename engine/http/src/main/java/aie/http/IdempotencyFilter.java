package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.IdempotencyKeys;
import aie.core.tenancy.api.IdempotencyKeys.Claim;
import aie.core.tenancy.api.IdempotencyKeys.Key;
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
 * application, and a request those checks refuse leaves the key free.
 *
 * <p>The answer is stored after the controller has given it. A process that dies in between, or a request that ends
 * in an exception no controller turned into an answer, leaves the key claimed until its lease runs out; then a retry
 * runs the request again (see {@link IdempotencyKeys}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    /** The contract allows 1 to 255 characters. */
    static final int MAX_KEY = 255;
    /** The fingerprint covers the whole body, so the body is read into memory, and only this much of it. */
    static final int MAX_BODY = 1024 * 1024;
    /**
     * A request attribute a controller sets to the top-level fields of its JSON answer that must not be stored, such as
     * a new key's secret. A replay then leaves them out, so no secret is kept for the 24 hours of a key.
     */
    static final String NOT_STORED = "aie.http.idempotency.not-stored";

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
        switch (keys.claim(key, fingerprint(request, body))) {
            case Claim.Acquired acquired -> run(new CachedBodyRequest(request, body), response, chain, key);
            case Claim.InUse inUse -> refuse(request, response, HttpServletResponse.SC_CONFLICT,
                    "idempotency_key_in_use", "A request with this " + HEADER + " is still running");
            case Claim.Reused reused -> refuse(request, response, 422,
                    "idempotency_key_reused", "This " + HEADER + " belongs to a different request");
            case Claim.Finished(int status, String answer) -> replay(response, status, answer);
        }
    }

    /** What the rest of the chain answers is stored first, then sent. A failure to store still sends it. */
    private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Key key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper answer = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, answer);
        try {
            keys.finish(key, answer.getStatus(), storable(request, answer.getContentAsByteArray()));
        } catch (RuntimeException e) {
            LOG.error("The answer to an {} could not be stored, so a retry after the lease runs the request again", HEADER, e);
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
    private static byte @Nullable [] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        return body.length > MAX_BODY ? null : body;
    }

    /** The method, the path and the body: the same key on another operation is another request. */
    private static String fingerprint(HttpServletRequest request, byte[] body) {
        String query = request.getQueryString();
        String target = request.getMethod() + " " + request.getServletPath() + (query == null ? "" : "?" + query) + "\n";
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
