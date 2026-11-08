package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.core.tenancy.api.ApiKeyManagement.CreatedKey;
import aie.http.api.IdentityApi;
import aie.http.api.model.CreateAnonymousSessionRequest;
import aie.http.api.model.CreateApiKeyRequest;
import aie.http.api.model.CreateSessionRequest;
import aie.http.api.model.CreatedApiKey;
import aie.http.api.model.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The identity operations of the contract. Creating and revoking keys work. The others answer 404, as their paths did
 * before this class existed, until their own points replace them: sessions in 11 and anonymous sessions in 12. The
 * generated interface has no defaults, so all of them have to be here.
 */
@RestController
class IdentityController implements IdentityApi {

    /** The contract allows a name of up to 100 characters. */
    static final int MAX_NAME = 100;

    private final ApiKeyManagement keys;
    private final HttpServletRequest request;

    IdentityController(ApiKeyManagement keys, HttpServletRequest request) {
        this.keys = keys;
        this.request = request;
    }

    /** A key of another application, an unknown one and a revoked one all read as missing. */
    @Override
    public ResponseEntity<Void> deleteApiKey(
            String keyId, @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        if (!keys.revoke(caller(), keyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Another key for the caller's application, so old and new keys work side by side during a rotation. Only a hash of
     * the key is kept, so the answer stored for an Idempotency-Key leaves the key out (contract: CreatedApiKey.key).
     */
    @Override
    public ResponseEntity<CreatedApiKey> createApiKey(CreateApiKeyRequest body, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        OffsetDateTime expiresAt = body.getExpiresAt();
        if (expiresAt != null && !expiresAt.toInstant().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expires_at must be in the future");
        }
        String name = body.getName();
        if (name != null && name.codePointCount(0, name.length()) > MAX_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name may be at most " + MAX_NAME + " characters");
        }
        CreatedKey created = keys.create(caller(), name, expiresAt == null ? null : expiresAt.toInstant());
        request.setAttribute(IdempotencyFilter.NOT_STORED, Set.of("key"));
        CreatedApiKey answer = new CreatedApiKey(created.keyId(), created.createdAt().atOffset(ZoneOffset.UTC))
                .key(created.key())
                .name(created.name())
                .expiresAt(created.expiresAt() == null ? null : created.expiresAt().atOffset(ZoneOffset.UTC));
        return ResponseEntity.status(HttpStatus.CREATED).body(answer);
    }

    @Override
    public ResponseEntity<Session> createSession(CreateSessionRequest body, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        throw notYet();
    }

    @Override
    public ResponseEntity<Session> createAnonymousSession(CreateAnonymousSessionRequest body,
            @Nullable String idempotencyKey, @Nullable String engineVersion, @Nullable String traceparent) {
        throw notYet();
    }

    /** ApiKeyFilter has put the caller here before any /v1 request reaches a controller. */
    private Owner caller() {
        return Objects.requireNonNull((Owner) request.getAttribute(ApiKeyFilter.CALLER), "No caller on a /v1 request");
    }

    private static ResponseStatusException notYet() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
