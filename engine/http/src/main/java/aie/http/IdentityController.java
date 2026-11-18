package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.core.tenancy.api.ApiKeyManagement.CreatedKey;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.api.SessionTokens.Issued;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The identity operations of the contract: creating and revoking keys, and creating sessions and anonymous sessions.
 * The generated interface has no defaults, so every operation has to be here.
 */
@RestController
class IdentityController implements IdentityApi {

    /** The contract allows a name of up to 100 characters. */
    static final int MAX_NAME = 100;
    /** The contract's KeyId: this prefix, then letters and digits, at most 64 characters in all. */
    private static final String KEY_PREFIX = "key_";
    private static final int MAX_KEY_ID = 64;
    private static final Pattern KEY_ID = Pattern.compile(KEY_PREFIX + "[0-9A-Za-z]+");

    private final ApiKeyManagement keys;
    private final EndUsers endUsers;
    private final SessionTokens sessionTokens;
    private final HttpServletRequest request;

    IdentityController(ApiKeyManagement keys, EndUsers endUsers, SessionTokens sessionTokens, HttpServletRequest request) {
        this.keys = keys;
        this.endUsers = endUsers;
        this.sessionTokens = sessionTokens;
        this.request = request;
    }

    /**
     * A key of another application, an unknown one and a revoked one all read as missing. A key ID the contract does not
     * allow is refused before that, and nothing is looked up for it, as for a conversation or message ID (point 36).
     */
    @Override
    public ResponseEntity<Void> deleteApiKey(
            String keyId, @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        String id = keyId(keyId);
        if (!keys.revoke(caller(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /** The key ID of the path, as the contract's KeyId allows it. The whole ID has to match, as a conversation ID does. */
    private static String keyId(String keyId) {
        if (!KEY_ID.matcher(keyId).matches() || keyId.length() > MAX_KEY_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key_id must be " + KEY_PREFIX
                    + " followed by 1 to " + (MAX_KEY_ID - KEY_PREFIX.length()) + " letters or digits");
        }
        return keyId;
    }

    /**
     * Another key for the caller's application, so old and new keys work side by side during a rotation. Only a hash of
     * the key is kept, so the answer stored for an Idempotency-Key leaves the key out (contract: CreatedApiKey.key). A
     * name takes no NUL, which the database cannot store (point 37).
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
        if (name != null && name.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name cannot hold a NUL character");
        }
        CreatedKey created = keys.create(caller(), name, expiresAt == null ? null : expiresAt.toInstant());
        request.setAttribute(IdempotencyFilter.NOT_STORED, Set.of("key"));
        CreatedApiKey answer = new CreatedApiKey(created.keyId(), created.createdAt().atOffset(ZoneOffset.UTC))
                .key(created.key())
                .name(created.name())
                .expiresAt(created.expiresAt() == null ? null : created.expiresAt().atOffset(ZoneOffset.UTC));
        return ResponseEntity.status(HttpStatus.CREATED).body(answer);
    }

    /**
     * A token for an end user of the caller's application, with the end user made the first time the host's ID is seen.
     * For On-Behalf-Of, the caller is already the lower tenant's application. The engine never stores a token, so the
     * answer stored for an Idempotency-Key leaves it out (contract: Session.token).
     */
    @Override
    public ResponseEntity<Session> createSession(CreateSessionRequest body, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        SessionRequests.Checked checked = SessionRequests.check(body, sessionTokens);
        Owner caller = caller();
        String endUserId = endUsers.resolve(caller, checked.externalId());
        Issued issued = sessionTokens.issue(new SessionTokens.Session(caller.tenantId(), caller.applicationId(), endUserId,
                checked.identityLevel(), checked.scopes(), checked.context(), checked.locale()));
        request.setAttribute(IdempotencyFilter.NOT_STORED, Set.of("token"));
        Session answer = new Session(issued.expiresAt().atOffset(ZoneOffset.UTC), endUserId, checked.identityLevel().wireName())
                .token(issued.token())
                .scopes(checked.scopes());
        return ResponseEntity.status(HttpStatus.CREATED).body(answer);
    }

    /**
     * A token with the lowest rights for a visitor of the host's site (blueprint 6.5): no scopes, no context, and an end
     * user of its own for this session. SiteKeyFilter has checked the site key and the origin, so the caller is the
     * key's application. The token is never stored, so a replay leaves it out, as it does for sessions.
     */
    @Override
    public ResponseEntity<Session> createAnonymousSession(CreateAnonymousSessionRequest body,
            @Nullable String idempotencyKey, @Nullable String engineVersion, @Nullable String traceparent) {
        String locale = SessionRequests.locale(body.getLocale());
        Owner caller = caller();
        String endUserId = endUsers.anonymous(caller);
        Issued issued = sessionTokens.issue(new SessionTokens.Session(caller.tenantId(), caller.applicationId(), endUserId,
                IdentityLevel.ANONYMOUS, List.of(), Map.of(), locale));
        request.setAttribute(IdempotencyFilter.NOT_STORED, Set.of("token"));
        Session answer = new Session(issued.expiresAt().atOffset(ZoneOffset.UTC), endUserId, IdentityLevel.ANONYMOUS.wireName())
                .token(issued.token())
                .scopes(List.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(answer);
    }

    /** ApiKeyFilter has put the caller here before any /v1 request reaches a controller. */
    private Owner caller() {
        return Objects.requireNonNull((Owner) request.getAttribute(ApiKeyFilter.CALLER), "No caller on a /v1 request");
    }

}
