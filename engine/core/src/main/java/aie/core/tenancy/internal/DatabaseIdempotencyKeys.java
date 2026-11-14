package aie.core.tenancy.internal;

import aie.core.tenancy.api.IdempotencyKeys;
import aie.ports.DatabasePort;
import aie.ports.Row;
import aie.ports.Tx;

/** {@link IdempotencyKeys} on the database. A unique key decides which of two requests at the same moment wins. */
public final class DatabaseIdempotencyKeys implements IdempotencyKeys {

    private static final String TAKE = """
            INSERT INTO tenancy_idempotency_keys (tenant_id, application_id, idempotency_key, fingerprint)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, application_id, idempotency_key) DO NOTHING
            """;
    private static final String READ = """
            SELECT fingerprint,
                   response_status,
                   response_body,
                   response_status IS NOT NULL AS finished,
                   expires_at <= now() AS expired,
                   locked_until <= now() AS abandoned
            FROM tenancy_idempotency_keys
            WHERE tenant_id = ? AND application_id = ? AND idempotency_key = ?
            FOR UPDATE
            """;
    private static final String TAKE_OVER = """
            UPDATE tenancy_idempotency_keys
            SET fingerprint = ?,
                response_status = NULL,
                response_body = NULL,
                locked_until = now() + interval '5 minutes',
                expires_at = now() + interval '24 hours'
            WHERE tenant_id = ? AND application_id = ? AND idempotency_key = ?
            """;
    private static final String FINISH = """
            UPDATE tenancy_idempotency_keys
            SET response_status = ?, response_body = ?
            WHERE tenant_id = ? AND application_id = ? AND idempotency_key = ? AND response_status IS NULL
            """;

    private static final String RELEASE = """
            DELETE FROM tenancy_idempotency_keys
            WHERE tenant_id = ? AND application_id = ? AND idempotency_key = ? AND response_status IS NULL
            """;

    private final DatabasePort database;

    public DatabaseIdempotencyKeys(DatabasePort database) {
        this.database = database;
    }

    @Override
    public Claim claim(Key key, String fingerprint) {
        return database.inTenantTx(key.tenantId(), tx -> {
            int taken = tx.update(TAKE, key.tenantId(), key.applicationId(), key.value(), fingerprint);
            return taken == 1 ? new Claim.Acquired() : decide(tx, key, fingerprint);
        });
    }

    /** The row is locked here, so two retries cannot both take over an abandoned key. */
    private static Claim decide(Tx tx, Key key, String fingerprint) {
        Held held = tx.query(READ, Held::of, key.tenantId(), key.applicationId(), key.value()).get(0);
        if (held.expired()) {
            return takeOver(tx, key, fingerprint);
        }
        if (!held.fingerprint().equals(fingerprint)) {
            return new Claim.Reused();
        }
        if (held.finished()) {
            return new Claim.Finished(held.status(), held.body());
        }
        return held.abandoned() ? takeOver(tx, key, fingerprint) : new Claim.InUse();
    }

    private static Claim takeOver(Tx tx, Key key, String fingerprint) {
        tx.update(TAKE_OVER, fingerprint, key.tenantId(), key.applicationId(), key.value());
        return new Claim.Acquired();
    }

    @Override
    public void finish(Key key, int status, String body) {
        database.inTenantTx(key.tenantId(), tx -> tx.update(FINISH, status, body, key.tenantId(), key.applicationId(), key.value()));
    }

    @Override
    public void release(Key key) {
        database.inTenantTx(key.tenantId(), tx -> tx.update(RELEASE, key.tenantId(), key.applicationId(), key.value()));
    }

    private record Held(String fingerprint, boolean finished, int status, String body, boolean expired, boolean abandoned) {

        static Held of(Row row) {
            boolean finished = row.flag("finished");
            return new Held(
                    row.string("fingerprint"),
                    finished,
                    finished ? Math.toIntExact(row.number("response_status")) : 0,
                    row.string("response_body"),
                    row.flag("expired"),
                    row.flag("abandoned"));
        }
    }
}
