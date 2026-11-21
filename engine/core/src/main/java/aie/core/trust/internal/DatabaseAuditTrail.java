package aie.core.trust.internal;

import aie.core.trust.api.Audit.Entry;
import aie.core.trust.api.Audit.Event;
import aie.core.trust.api.AuditTrail;
import aie.core.trust.api.DataClass;
import aie.ports.DatabasePort;
import aie.ports.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link AuditTrail} on the database (M3-5-2): a tenant's rows of {@code trust_audit_records}, read back a page at a
 * time, each page after the last entry of the one before, along the table's index on time and ID. It reads the HMACs a
 * sensitive entry keeps, and needs no key for that.
 */
public final class DatabaseAuditTrail implements AuditTrail {

    /** How many entries one page of a read holds. */
    public static final int PAGE = 500;

    private static final String READ = """
            SELECT record_id, created_at, request_id, tenant_id, application_id, end_user_id, conv_id, message_id, task, event,
                data_class, model, prompt, rule, question_hmac, answer_hmac, key_id
            FROM trust_audit_records
            WHERE tenant_id = ?""";
    private static final String FROM = " AND created_at >= ?";
    private static final String TO = " AND created_at < ?";
    private static final String AFTER = " AND (created_at, record_id) > (?, ?)";
    private static final String ORDER = " ORDER BY created_at, record_id LIMIT ?";

    private final DatabasePort database;
    private final int page;

    public DatabaseAuditTrail(DatabasePort database) {
        this(database, PAGE);
    }

    /** With pages of another size, so that a test need not add 500 entries to read more than one page. */
    public DatabaseAuditTrail(DatabasePort database, int page) {
        if (page < 1) {
            throw new IllegalArgumentException("A page of the audit holds at least one entry, not " + page);
        }
        this.database = Objects.requireNonNull(database, "The audit is kept in the database");
        this.page = page;
    }

    /**
     * Each page starts after the last entry of the page before, by time and then ID, so every entry is read once,
     * wherever the pages fall. A page that is not full is the last one.
     */
    @Override
    public void read(String tenantId, Instant from, Instant to, Consumer<Recorded> each) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Reading the audit needs the tenant");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("The audit is read from a time before the one it is read to, not from " + from
                    + " to " + to);
        }
        Recorded last = null;
        List<Recorded> found;
        do {
            found = page(tenantId, from, to, last);
            found.forEach(each);
            if (!found.isEmpty()) {
                last = found.getLast();
            }
        } while (found.size() == page);
    }

    /** One page of the tenant's entries in the window: those after this entry, or from the first one. */
    private List<Recorded> page(String tenantId, Instant from, Instant to, Recorded after) {
        StringBuilder sql = new StringBuilder(READ);
        List<Object> arguments = new ArrayList<>(List.of(tenantId));
        if (from != null) {
            sql.append(FROM);
            arguments.add(from);
        }
        if (to != null) {
            sql.append(TO);
            arguments.add(to);
        }
        if (after != null) {
            sql.append(AFTER);
            arguments.add(after.createdAt());
            arguments.add(after.recordId());
        }
        sql.append(ORDER);
        arguments.add(page);
        return database.inTenantTx(tenantId, tx -> tx.query(sql.toString(), DatabaseAuditTrail::recorded, arguments.toArray()));
    }

    private static Recorded recorded(Row row) {
        return new Recorded(row.string("record_id"), row.time("created_at"), new Entry(row.string("request_id"),
                row.string("tenant_id"), row.string("application_id"), row.string("end_user_id"), row.string("conv_id"),
                row.string("message_id"), row.string("task"), Event.valueOf(row.string("event").toUpperCase(Locale.ROOT)),
                DataClass.fromWireName(row.string("data_class")).orElse(null), row.string("model"), row.string("prompt"),
                row.string("rule")), row.string("question_hmac"), row.string("answer_hmac"), row.string("key_id"));
    }
}
