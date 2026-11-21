package aie.core.trust.internal;

import aie.core.Ids;
import aie.core.trust.api.Audit;
import aie.ports.DatabasePort;
import java.util.Objects;

/**
 * {@link Audit} on the database: each entry is a row of {@code trust_audit_records}, added in a tenant transaction of its
 * own. The table lets the engine read and add rows, never change or delete them, and its checks refuse an entry whose
 * fields do not fit its event, so what the code gets wrong never goes in.
 */
public final class DatabaseAudit implements Audit {

    private static final String ADD = """
            INSERT INTO trust_audit_records (record_id, tenant_id, request_id, application_id, end_user_id, conv_id, message_id,
                task, event, data_class, model, prompt, rule)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DatabasePort database;

    public DatabaseAudit(DatabasePort database) {
        this.database = Objects.requireNonNull(database, "The audit is kept in the database");
    }

    @Override
    public void add(Entry entry) {
        database.inTenantTx(entry.tenantId(), tx -> tx.update(ADD, Ids.next("aud"), entry.tenantId(), entry.requestId(),
                entry.applicationId(), entry.endUserId(), entry.conversationId(), entry.messageId(), entry.task(),
                entry.event().wireName(), entry.dataClass() == null ? null : entry.dataClass().wireName(), entry.model(),
                entry.prompt(), entry.rule()));
    }
}
