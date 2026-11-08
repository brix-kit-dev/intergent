package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.http.api.IdentityApi;
import aie.http.api.model.CreateAnonymousSessionRequest;
import aie.http.api.model.CreateApiKeyRequest;
import aie.http.api.model.CreateSessionRequest;
import aie.http.api.model.CreatedApiKey;
import aie.http.api.model.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The identity operations of the contract. Revoking a key works. The others answer 404, as their paths did before this
 * class existed, until their own points replace them: creating a key in 10-5, sessions in 11 and anonymous sessions
 * in 12. The generated interface has no defaults, so all of them have to be here.
 */
@RestController
class IdentityController implements IdentityApi {

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

    @Override
    public ResponseEntity<CreatedApiKey> createApiKey(CreateApiKeyRequest body, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        throw notYet();
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
