package aie.core.tenancy.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeys;
import aie.ports.Tx;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Issuing a key, the same way for admin's first key and for keys created through the public API. */
final class KeyIssuing {

    /** The key takes the application's environment and the tenant's cell, so it can be routed without the database. */
    private static final String KEY_PARTS = """
            SELECT a.environment, t.cell_id
            FROM tenancy_applications a
            JOIN tenancy_tenants t ON t.tenant_id = a.tenant_id
            WHERE a.application_id = ?
            """;
    private static final String ADD_KEY = """
            INSERT INTO tenancy_api_keys (key_id, tenant_id, application_id, key_hash, name, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING created_at
            """;

    private KeyIssuing() {
    }

    /** A key just issued. The key itself exists only here, and only its hash went into the database. */
    record Issued(String keyId, String key, Instant createdAt) {
    }

    /**
     * Issues a key inside the tenant's transaction. Row-level security hides the applications of other tenants, so one
     * of them reads as missing and gets nothing. The name and the expiry may be null.
     */
    static Optional<Issued> issue(Tx tx, String tenantId, String applicationId, String name, Instant expiresAt) {
        List<String> keys = tx.query(KEY_PARTS, row -> ApiKeys.issue(row.string("environment"), row.string("cell_id")), applicationId);
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        String keyId = Ids.next("key");
        String key = keys.getFirst();
        Instant createdAt = tx.query(ADD_KEY, row -> row.time("created_at"), keyId, tenantId, applicationId, ApiKeys.hash(key), name, expiresAt)
                .getFirst();
        return Optional.of(new Issued(keyId, key, createdAt));
    }
}
