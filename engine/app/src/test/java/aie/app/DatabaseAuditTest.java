package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.trust.api.Audit;
import aie.core.trust.api.Audit.Entry;
import aie.core.trust.api.Audit.Event;
import aie.core.trust.api.DataClass;
import aie.core.trust.internal.DatabaseAudit;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M3-5-1: the audit on the real scripts, as engine_app through a pool. Each entry is kept as it came, an entry whose
 * fields do not fit its event is refused, and nothing engine_app may do changes, deletes or locks an entry once added
 * (blueprint 9.4). That each tenant reads only its own entries, TenantIsolationTest shows for every tenant table.
 */
@Testcontainers
class DatabaseAuditTest {

    private static final String TENANT = "ten_a";
    /** The columns of an entry, as one line, in the order they were added. */
    private static final String ENTRIES = """
            SELECT concat_ws(' ', tenant_id, coalesce(request_id, '-'), application_id, end_user_id, conv_id,
                coalesce(message_id, '-'), task, event, coalesce(data_class, '-'), coalesce(model, '-'), coalesce(prompt, '-'),
                coalesce(rule, '-'))
            FROM engine.trust_audit_records ORDER BY created_at, record_id
            """;

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgPool pool;
    private static Audit audit;

    @BeforeAll
    static void createRolesMigrateAndOpenAPool() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 2);
        audit = new DatabaseAudit(pool);
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    @BeforeEach
    void emptyTheAudit() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.trust_audit_records");
    }

    /** An entry of a call of this tenant, which ended so, with a rule when it was blocked. */
    private static Entry call(Event event, String rule) {
        return new Entry("req_1", TENANT, "app_a", "eu_a", "conv_a", "msg_a", "customer_chat", event, DataClass.PERSONAL, "fake",
                "customer_chat/fake/v1", rule);
    }

    /** An entry of a text the input check blocked. */
    private static Entry blocked() {
        return new Entry("req_2", TENANT, "app_a", "eu_a", "conv_a", null, "customer_chat", Event.INPUT_BLOCKED, null, null, null,
                "instruction_override");
    }

    /** Each entry is kept as it came, with an ID of its own and the time the database added it. */
    @Test
    void eachEntryIsKeptAsItCame() throws SQLException {
        audit.add(blocked());
        audit.add(call(Event.MODEL_CALLED, null));
        audit.add(call(Event.COMPLETED, null));
        audit.add(call(Event.CANCELLED, null));
        audit.add(call(Event.FAILED, null));
        audit.add(call(Event.OUTPUT_BLOCKED, "prompt_leak"));
        audit.add(new Entry(null, TENANT, "app_a", "eu_a", "conv_a", "msg_b", "customer_chat", Event.MODEL_CALLED, DataClass.PUBLIC,
                "fake", "customer_chat/fake/v1", null));

        String called = " app_a eu_a conv_a msg_a customer_chat ";
        assertThat(TestDatabase.firstColumn(DATABASE, ENTRIES)).containsExactly(
                "ten_a req_2 app_a eu_a conv_a - customer_chat input_blocked - - - instruction_override",
                "ten_a req_1" + called + "model_called personal fake customer_chat/fake/v1 -",
                "ten_a req_1" + called + "completed personal fake customer_chat/fake/v1 -",
                "ten_a req_1" + called + "cancelled personal fake customer_chat/fake/v1 -",
                "ten_a req_1" + called + "failed personal fake customer_chat/fake/v1 -",
                "ten_a req_1" + called + "output_blocked personal fake customer_chat/fake/v1 prompt_leak",
                "ten_a - app_a eu_a conv_a msg_b customer_chat model_called public fake customer_chat/fake/v1 -");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(DISTINCT record_id) || ' ' || bool_and(record_id ~ '^aud_[0-9A-Z]{26}$')"
                + " || ' ' || bool_and(created_at > now() - interval '1 minute') FROM engine.trust_audit_records"))
                .containsExactly("7 true true");
    }

    /**
     * The table refuses an entry whose fields do not fit its event: a blocked input names its rule and nothing of a call,
     * a call names its message, class, model and prompt, and only a blocked output names a rule as well. Who asked and
     * the task are never missing. An event or a class it does not know is refused by its own check, though the entry
     * fits otherwise. Nothing goes in then.
     */
    @Test
    void anEntryThatDoesNotFitItsEventIsRefused() throws SQLException {
        Entry called = call(Event.MODEL_CALLED, null);
        Entry stopped = call(Event.OUTPUT_BLOCKED, "prompt_leak");
        Entry input = blocked();
        Map<String, Entry> wrong = Map.ofEntries(
                Map.entry("a blocked input with a message", with(input, "message", "msg_a")),
                Map.entry("a blocked input with a class", with(input, "class", "public")),
                Map.entry("a blocked input with a model", with(input, "model", "fake")),
                Map.entry("a blocked input with a prompt", with(input, "prompt", "customer_chat/fake/v1")),
                Map.entry("a blocked input without its rule", with(input, "rule", null)),
                Map.entry("a call without its message", with(called, "message", null)),
                Map.entry("a call without its class", with(called, "class", null)),
                Map.entry("a call without its model", with(called, "model", null)),
                Map.entry("a call without its prompt", with(called, "prompt", null)),
                Map.entry("a call with a rule", with(called, "rule", "prompt_leak")),
                Map.entry("a completed call with a rule", with(call(Event.COMPLETED, null), "rule", "prompt_leak")),
                Map.entry("a blocked output without its rule", with(stopped, "rule", null)),
                Map.entry("an entry without its application", with(called, "application", null)),
                Map.entry("an entry without its end user", with(called, "end user", null)),
                Map.entry("an entry without its conversation", with(called, "conversation", null)),
                Map.entry("an entry without its task", with(called, "task", null)));

        wrong.forEach((what, entry) -> assertThatThrownBy(() -> audit.add(entry)).as(what).isInstanceOf(DatabaseException.class));
        String call = "INSERT INTO trust_audit_records (record_id, tenant_id, application_id, end_user_id, conv_id, message_id,"
                + " task, event, data_class, model, prompt) VALUES ('aud_x', 'ten_a', 'app_a', 'eu_a', 'conv_a', 'msg_a',"
                + " 'customer_chat', '%s', '%s', 'fake', 'customer_chat/fake/v1')";
        Map<String, String> unknown = Map.of(
                call.formatted("model_answered", "public"), "trust_audit_records_event_check",
                call.formatted("model_called", "secret"), "trust_audit_records_data_class_check");
        unknown.forEach((sql, check) -> assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> tx.update(sql))).as(sql)
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("violates check constraint \"" + check + "\""));
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.trust_audit_records")).containsExactly("0");

        audit.add(called);
        audit.add(stopped);
        audit.add(input);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.trust_audit_records")).containsExactly("3");
    }

    /**
     * Blueprint 9.4: an entry is only added. engine_app may read entries and add them, and every way it might change,
     * delete or lock one is refused, by the table's grants or because it does not own the table. The entry stays as it
     * was.
     */
    @Test
    void anEntryCannotBeChangedDeletedOrLocked() throws SQLException {
        audit.add(call(Event.COMPLETED, null));
        String id = TestDatabase.firstColumn(DATABASE, "SELECT record_id FROM engine.trust_audit_records").getFirst();
        List<String> before = TestDatabase.firstColumn(DATABASE, ENTRIES);
        Map<String, String> refusals = Map.ofEntries(
                Map.entry("UPDATE trust_audit_records SET rule = 'changed'", "permission denied"),
                Map.entry("UPDATE trust_audit_records SET event = 'failed' WHERE record_id = '" + id + "'", "permission denied"),
                Map.entry("DELETE FROM trust_audit_records", "permission denied"),
                Map.entry("DELETE FROM trust_audit_records WHERE record_id = '" + id + "'", "permission denied"),
                Map.entry("TRUNCATE trust_audit_records", "permission denied"),
                Map.entry("INSERT INTO trust_audit_records (record_id, tenant_id, application_id, end_user_id, conv_id,"
                        + " message_id, task, event, data_class, model, prompt) VALUES ('" + id + "', 'ten_a', 'app_a', 'eu_a',"
                        + " 'conv_a', 'msg_a', 'customer_chat', 'failed', 'public', 'fake', 'customer_chat/fake/v1')"
                        + " ON CONFLICT (record_id) DO UPDATE SET event = 'failed'", "permission denied"),
                Map.entry("MERGE INTO trust_audit_records t USING (SELECT 1) s ON true WHEN MATCHED THEN UPDATE SET rule = 'changed'",
                        "permission denied"),
                Map.entry("MERGE INTO trust_audit_records t USING (SELECT 1) s ON true WHEN MATCHED THEN DELETE", "permission denied"),
                Map.entry("SELECT record_id FROM trust_audit_records FOR UPDATE", "permission denied"),
                Map.entry("SELECT record_id FROM trust_audit_records FOR SHARE", "permission denied"),
                Map.entry("ALTER TABLE trust_audit_records DISABLE ROW LEVEL SECURITY", "must be owner"),
                Map.entry("ALTER TABLE trust_audit_records NO FORCE ROW LEVEL SECURITY", "must be owner"),
                Map.entry("ALTER TABLE trust_audit_records DROP CONSTRAINT trust_audit_records_fit_the_event", "must be owner"),
                Map.entry("DROP POLICY tenant_isolation ON trust_audit_records", "must be owner"),
                Map.entry("DROP TABLE trust_audit_records", "must be owner"));

        refusals.forEach((sql, refusal) -> assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> sql.startsWith("SELECT")
                        ? tx.query(sql, row -> row.string("record_id")).size() : tx.update(sql)))
                .as(sql).isInstanceOf(DatabaseException.class).hasStackTraceContaining(refusal));

        assertThat(TestDatabase.firstColumn(DATABASE, ENTRIES)).isEqualTo(before);
        List<String> read = pool.inTenantTx(TENANT, tx -> tx.query("SELECT event FROM trust_audit_records", row -> row.string("event")));
        assertThat(read).containsExactly("completed");
    }

    /** The entry with one field set to this value, so that a test can make an entry that does not fit. */
    private static Entry with(Entry entry, String field, String value) {
        return new Entry(entry.requestId(), entry.tenantId(),
                field.equals("application") ? value : entry.applicationId(),
                field.equals("end user") ? value : entry.endUserId(),
                field.equals("conversation") ? value : entry.conversationId(),
                field.equals("message") ? value : entry.messageId(),
                field.equals("task") ? value : entry.task(),
                entry.event(),
                field.equals("class") ? (value == null ? null : DataClass.fromWireName(value).orElseThrow()) : entry.dataClass(),
                field.equals("model") ? value : entry.model(),
                field.equals("prompt") ? value : entry.prompt(),
                field.equals("rule") ? value : entry.rule());
    }
}
