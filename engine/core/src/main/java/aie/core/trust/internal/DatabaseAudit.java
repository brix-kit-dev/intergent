package aie.core.trust.internal;

import aie.core.Ids;
import aie.core.trust.api.Audit;
import aie.core.trust.api.DataClass;
import aie.ports.DatabasePort;
import java.util.Objects;

/**
 * {@link Audit} on the database: each entry is a row of {@code trust_audit_records}, added in a tenant transaction of its
 * own. The table lets the engine read and add rows, never change or delete them, and its checks refuse an entry whose
 * fields do not fit its event, so what the code gets wrong never goes in. A sensitive entry keeps the HMAC of its
 * question or its answer by the tenant's audit key instead of the text, with the key's ID, in the same transaction as the
 * key is read or made (M3-5-3); one that came with no content makes no key and keeps none, and the table refuses it.
 */
public final class DatabaseAudit implements Audit {

    private static final String ADD = """
            INSERT INTO trust_audit_records (record_id, tenant_id, request_id, application_id, end_user_id, conv_id, message_id,
                task, event, data_class, model, prompt, rule, question_hmac, answer_hmac, key_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DatabasePort database;
    private final TenantKeys keys;

    /**
     * @param masterKey the master key that wraps each tenant's audit key, 32 bytes, from {@code SecretPort}
     * @throws IllegalArgumentException for a master key that is not 32 bytes
     */
    public DatabaseAudit(DatabasePort database, byte[] masterKey) {
        this.database = Objects.requireNonNull(database, "The audit is kept in the database");
        this.keys = new TenantKeys(masterKey);
    }

    @Override
    public void add(Entry entry, Content content) {
        Objects.requireNonNull(content, "An entry's content is Content.NONE when it has none");
        database.inTenantTx(entry.tenantId(), tx -> {
            String questionHmac = null;
            String answerHmac = null;
            String keyId = null;
            if (entry.dataClass() == DataClass.SENSITIVE && (content.question() != null || content.answer() != null)) {
                TenantKeys.Key key = keys.of(tx, entry.tenantId());
                keyId = key.keyId();
                questionHmac = content.question() == null ? null : key.hmac("question", content.question());
                answerHmac = content.answer() == null ? null : key.hmac("answer", content.answer());
            }
            return tx.update(ADD, Ids.next("aud"), entry.tenantId(), entry.requestId(), entry.applicationId(), entry.endUserId(),
                    entry.conversationId(), entry.messageId(), entry.task(), entry.event().wireName(),
                    entry.dataClass() == null ? null : entry.dataClass().wireName(), entry.model(), entry.prompt(), entry.rule(),
                    questionHmac, answerHmac, keyId);
        });
    }
}
