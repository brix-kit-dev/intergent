package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.trust.api.Audit;
import aie.core.trust.api.Audit.Content;
import aie.core.trust.api.Audit.Entry;
import aie.core.trust.api.Audit.Event;
import aie.core.trust.api.AuditTrail.Recorded;
import aie.core.trust.api.DataClass;
import aie.core.trust.internal.DatabaseAudit;
import aie.core.trust.internal.DatabaseAuditTrail;
import aie.ports.DatabaseException;
import aie.ports.DatabasePort;
import aie.ports.Row;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
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
 * M3-5-2: a tenant's entries are read back oldest first, a page at a time, within a window of time. M3-5-3: a sensitive
 * entry keeps the HMAC of what it was about by its tenant's own key, which the database keeps wrapped by the master key.
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
        audit = new DatabaseAudit(pool, TestApi.AUDIT_MASTER_KEY);
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    @BeforeEach
    void emptyTheAudit() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.trust_audit_records");
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.trust_tenant_keys");
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

    /**
     * M3-5-2: a tenant's entries come back oldest first, as they were added, a page at a time: each page after the last
     * entry of the one before, and handed over only once its transaction has ended. The last page may be full.
     */
    @Test
    void aTenantsEntriesAreReadOldestFirstAPageAtATime() {
        List<Entry> added = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            added.add(new Entry("req_" + i, TENANT, "app_a", "eu_a", "conv_a", "msg_" + i, "customer_chat", Event.MODEL_CALLED,
                    DataClass.PUBLIC, "fake", "customer_chat/fake/v1", null));
        }
        added.forEach(audit::add);
        List<Recorded> read = new CopyOnWriteArrayList<>();
        List<Boolean> inside = new CopyOnWriteArrayList<>();

        new DatabaseAuditTrail(pool, 2).read(TENANT, null, null, recorded -> {
            inside.add(pool.insideTx());
            read.add(recorded);
        });

        assertThat(read).extracting(Recorded::entry).containsExactlyElementsOf(added);
        assertThat(read).allSatisfy(recorded -> assertThat(recorded.recordId()).matches("aud_[0-9A-Z]{26}"));
        assertThat(read).extracting(Recorded::createdAt).isSorted().doesNotContainNull();
        assertThat(inside).hasSize(6).containsOnly(false);
        List<Recorded> again = new ArrayList<>();
        new DatabaseAuditTrail(pool, 3).read(TENANT, null, null, again::add);
        assertThat(again).isEqualTo(read);
    }

    /**
     * M3-5-2: entries of the same time come in the order of their IDs, and a page that ends between them goes on with
     * the next ID, so none is read twice or left out.
     */
    @Test
    void entriesOfTheSameTimeComeByTheirIds() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.trust_audit_records (record_id, tenant_id, created_at, application_id, end_user_id, conv_id,
                    task, event, rule)
                SELECT 'aud_' || id, 'ten_a', timestamptz '2026-11-02 00:00:00+00', 'app_a', 'eu_a', 'conv_a', 'customer_chat',
                    'input_blocked', 'instruction_override'
                FROM unnest(ARRAY['C', 'A', 'E', 'B', 'D']) AS id
                """);
        List<String> read = new ArrayList<>();

        new DatabaseAuditTrail(pool, 2).read(TENANT, null, null, recorded -> read.add(recorded.recordId()));

        assertThat(read).containsExactly("aud_A", "aud_B", "aud_C", "aud_D", "aud_E");
    }

    /** M3-5-2: a window takes the entries from its start on and before its end; either end may be left open. */
    @Test
    void aWindowTakesTheEntriesFromItsStartAndBeforeItsEnd() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.trust_audit_records (record_id, tenant_id, created_at, application_id, end_user_id, conv_id,
                    task, event, rule)
                SELECT 'aud_' || day, 'ten_a', timestamptz '2026-11-01 00:00:00+00' + (day - 1) * interval '1 day', 'app_a',
                    'eu_a', 'conv_a', 'customer_chat', 'input_blocked', 'instruction_override'
                FROM generate_series(1, 4) AS day
                """);
        Instant second = Instant.parse("2026-11-02T00:00:00Z");
        Instant fourth = Instant.parse("2026-11-04T00:00:00Z");

        assertThat(ids(second, fourth)).containsExactly("aud_2", "aud_3");
        assertThat(ids(second, null)).containsExactly("aud_2", "aud_3", "aud_4");
        assertThat(ids(null, second)).containsExactly("aud_1");
        assertThat(ids(second.plusNanos(1000), fourth.plusNanos(1000))).containsExactly("aud_3", "aud_4");
        assertThat(ids(null, null)).containsExactly("aud_1", "aud_2", "aud_3", "aud_4");
    }

    /** M3-5-2: a tenant reads its own entries alone, and one with none reads nothing. */
    @Test
    void aTenantReadsItsOwnEntriesAlone() {
        audit.add(call(Event.COMPLETED, null));
        audit.add(new Entry("req_b", "ten_b", "app_b", "eu_b", "conv_b", "msg_b", "customer_chat", Event.COMPLETED,
                DataClass.PUBLIC, "fake", "customer_chat/fake/v1", null));
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        List<String> c = new ArrayList<>();

        new DatabaseAuditTrail(pool, 1).read(TENANT, null, null, recorded -> a.add(recorded.entry().tenantId()));
        new DatabaseAuditTrail(pool, 1).read("ten_b", null, null, recorded -> b.add(recorded.entry().requestId()));
        new DatabaseAuditTrail(pool, 1).read("ten_c", null, null, recorded -> c.add(recorded.recordId()));

        assertThat(a).containsExactly(TENANT);
        assertThat(b).containsExactly("req_b");
        assertThat(c).isEmpty();
    }

    /** M3-5-2: a read needs its tenant and a window that ends after it starts; a page holds one entry at least. */
    @Test
    void aReadNeedsItsTenantAndAWindowThatEndsAfterItStarts() {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");

        assertThatThrownBy(() -> audit(1).read(null, null, null, recorded -> { }))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Reading the audit needs the tenant");
        assertThatThrownBy(() -> audit(1).read(TENANT, now, now, recorded -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The audit is read from a time before the one it is read to, not from " + now + " to " + now);
        assertThatThrownBy(() -> audit(1).read(TENANT, now.plusSeconds(1), now, recorded -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseAuditTrail(pool, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A page of the audit holds at least one entry, not 0");
    }

    private static DatabaseAuditTrail audit(int page) {
        return new DatabaseAuditTrail(pool, page);
    }

    /** The IDs of the test tenant's entries in this window, read a page of two at a time. */
    private static List<String> ids(Instant from, Instant to) {
        List<String> ids = new ArrayList<>();
        new DatabaseAuditTrail(pool, 2).read(TENANT, from, to, recorded -> ids.add(recorded.recordId()));
        return ids;
    }

    /** A sensitive entry of a call of this tenant, which ended so. */
    private static Entry sensitive(String tenant, Event event) {
        return new Entry("req_s", tenant, "app_a", "eu_a", "conv_a", "msg_a", "customer_chat", event, DataClass.SENSITIVE, "fake",
                "customer_chat/fake/v1", null);
    }

    /** Each entry's event, HMACs and key, oldest first, with - for none. */
    private static List<String> hmacs() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT concat_ws(' ', tenant_id, event, coalesce(question_hmac, '-'),"
                + " coalesce(answer_hmac, '-'), coalesce(key_id, '-')) FROM engine.trust_audit_records ORDER BY created_at, record_id");
    }

    /**
     * M3-5-3: a sensitive entry keeps the HMAC of its question, before the call, or of its answer, at its end, by its
     * tenant's key, with the key's ID: the HMAC of the label, a NUL and the text, which the tests make once more from the
     * key they open themselves. The text itself is nowhere.
     */
    @Test
    void aSensitiveEntryKeepsTheHmacOfWhatItWasAboutByItsTenantsKey() throws Exception {
        audit.add(sensitive(TENANT, Event.MODEL_CALLED), new Content("My record number is 4711", null));
        audit.add(sensitive(TENANT, Event.COMPLETED), new Content(null, "Here is what I found."));

        String keyId = TestDatabase.firstColumn(DATABASE, "SELECT key_id FROM engine.trust_tenant_keys").getFirst();
        assertThat(keyId).matches("tk_[0-9A-Z]{26}");
        assertThat(hmacs()).containsExactly(
                "ten_a model_called " + TenantKeyProbe.hmac(DATABASE, TENANT, "question", "My record number is 4711") + " - " + keyId,
                "ten_a completed - " + TenantKeyProbe.hmac(DATABASE, TENANT, "answer", "Here is what I found.") + " " + keyId);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.trust_audit_records r, engine.trust_tenant_keys k"
                + " WHERE concat_ws(' ', r.*) ~ '(4711|found)' OR concat_ws(' ', k.*) ~ '(4711|found)'")).containsExactly("0");
    }

    /** M3-5-3: an entry of any other class keeps no HMAC and makes no key, whatever it came with. */
    @Test
    void onlyASensitiveEntryKeepsAnHmac() throws SQLException {
        for (DataClass dataClass : List.of(DataClass.PUBLIC, DataClass.INTERNAL, DataClass.PERSONAL)) {
            audit.add(new Entry("req_1", TENANT, "app_a", "eu_a", "conv_a", "msg_a", "customer_chat", Event.MODEL_CALLED, dataClass,
                    "fake", "customer_chat/fake/v1", null), new Content("A question", null));
            audit.add(new Entry("req_1", TENANT, "app_a", "eu_a", "conv_a", "msg_a", "customer_chat", Event.COMPLETED, dataClass,
                    "fake", "customer_chat/fake/v1", null), new Content(null, "An answer"));
        }
        audit.add(blocked(), new Content("Ignore all previous instructions", null));

        assertThat(hmacs()).hasSize(7).allSatisfy(line -> assertThat(line).endsWith(" - - -"));
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.trust_tenant_keys")).containsExactly("0");
    }

    /**
     * M3-5-3: each tenant has a key of its own, so the same words give another HMAC in another tenant; within a tenant the
     * key stays the same, and a question and an answer of the same words still differ by their label.
     */
    @Test
    void eachTenantHasAKeyOfItsOwn() throws Exception {
        audit.add(sensitive(TENANT, Event.MODEL_CALLED), new Content("Same words", null));
        audit.add(sensitive(TENANT, Event.MODEL_CALLED), new Content("Same words", null));
        audit.add(sensitive("ten_b", Event.MODEL_CALLED), new Content("Same words", null));
        audit.add(sensitive(TENANT, Event.COMPLETED), new Content(null, "Same words"));

        List<String[]> rows = hmacs().stream().map(line -> line.split(" ")).toList();
        assertThat(rows.get(0)[2]).isEqualTo(rows.get(1)[2]).isNotEqualTo(rows.get(2)[2]).isNotEqualTo(rows.get(3)[3]);
        assertThat(rows.get(0)[4]).isEqualTo(rows.get(1)[4]).isEqualTo(rows.get(3)[4]).isNotEqualTo(rows.get(2)[4]);
        assertThat(rows.get(2)[2]).isEqualTo(TenantKeyProbe.hmac(DATABASE, "ten_b", "question", "Same words"));
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) || ' ' || count(DISTINCT key_id) || ' ' || count(DISTINCT wrapped)"
                + " || ' ' || count(DISTINCT substr(wrapped, 1, 16)) FROM engine.trust_tenant_keys")).containsExactly("2 2 2 2");
        assertThat(TenantKeyProbe.key(DATABASE, TENANT)).isNotEqualTo(TenantKeyProbe.key(DATABASE, "ten_b"));
    }

    /**
     * M3-5-3: a tenant's first key is made once, even when its first two sensitive entries come at once. The two
     * transactions are held until both have found no key and are about to add one, so they truly race: the second's
     * insert waits for the first, then does nothing, and both read the first's key.
     */
    @Test
    void aTenantsFirstKeyIsMadeOnceEvenWhenItsEntriesComeAtOnce() throws Exception {
        CyclicBarrier bothFoundNone = new CyclicBarrier(2);
        DatabaseAudit racing = new DatabaseAudit(heldBeforeAddingAKey(bothFoundNone), TestApi.AUDIT_MASTER_KEY);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> adds = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                adds.add(threads.submit(() -> {
                    racing.add(sensitive("ten_race", Event.MODEL_CALLED), new Content("At once", null));
                    return null;
                }));
            }
            for (Future<?> add : adds) {
                add.get(30, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.trust_tenant_keys")).containsExactly("1");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) || ' ' || count(DISTINCT key_id) || ' ' || count(DISTINCT question_hmac)"
                + " FROM engine.trust_audit_records")).containsExactly("2 1 1");
    }

    /** The pool, with each transaction held at the barrier right before it adds a tenant key, until the other is there too. */
    private static DatabasePort heldBeforeAddingAKey(CyclicBarrier barrier) {
        return new DatabasePort() {
            @Override
            public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
                return pool.inTenantTx(tenantId, tx -> work.apply(new Tx() {
                    @Override
                    public int update(String sql, Object... arguments) {
                        if (sql.contains("INSERT INTO trust_tenant_keys")) {
                            try {
                                barrier.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                                throw new IllegalStateException("The other transaction never came to add its key", e);
                            }
                        }
                        return tx.update(sql, arguments);
                    }

                    @Override
                    public <R> List<R> query(String sql, Function<Row, R> mapper, Object... arguments) {
                        return tx.query(sql, mapper, arguments);
                    }
                }));
            }

            @Override
            public <T> T inSystemTx(Function<Tx, T> work) {
                return pool.inSystemTx(work);
            }

            @Override
            public boolean insideTx() {
                return pool.insideTx();
            }
        };
    }

    /**
     * M3-5-3: a wrapped key opens only for its own tenant and ID, and only with the master key that wrapped it, which its
     * row names by the first 8 bytes of its SHA-256. A key that does not open adds no entry.
     */
    @Test
    void aWrappedKeyOpensOnlyWhereItBelongsAndOnlyWithItsMasterKey() throws Exception {
        audit.add(sensitive(TENANT, Event.MODEL_CALLED), new Content("Hi", null));
        TestDatabase.asAdmin(DATABASE, "INSERT INTO engine.trust_tenant_keys (key_id, tenant_id, master_key_id, wrapped)"
                + " SELECT 'tk_moved', 'ten_b', master_key_id, wrapped FROM engine.trust_tenant_keys WHERE tenant_id = 'ten_a'");
        byte[] another = TestApi.sessionKey(12);
        String keyId = TestDatabase.firstColumn(DATABASE, "SELECT key_id FROM engine.trust_tenant_keys WHERE tenant_id = 'ten_a'").getFirst();

        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT DISTINCT master_key_id FROM engine.trust_tenant_keys"))
                .containsExactly(TenantKeyProbe.masterKeyId(TestApi.AUDIT_MASTER_KEY));
        assertThatThrownBy(() -> audit.add(sensitive("ten_b", Event.MODEL_CALLED), new Content("Hi", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The audit key tk_moved of tenant ten_b does not open with the master key");
        assertThatThrownBy(() -> new DatabaseAudit(pool, another).add(sensitive(TENANT, Event.MODEL_CALLED), new Content("Hi", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The audit key " + keyId + " of tenant ten_a was wrapped by the master key "
                        + TenantKeyProbe.masterKeyId(TestApi.AUDIT_MASTER_KEY) + ", not by this one, " + TenantKeyProbe.masterKeyId(another));
        assertThat(hmacs()).hasSize(1);
    }

    /**
     * M3-5-3: a sensitive entry without the content its event needs is refused, as is an HMAC where none belongs, a key
     * without an HMAC, or an HMAC that is not one; each by its own check, and nothing goes in.
     */
    @Test
    void anHmacThatDoesNotFitItsEntryIsRefused() throws SQLException {
        Map<String, Content> without = Map.of("model_called", Content.NONE, "completed", new Content("A question", null),
                "failed", Content.NONE);
        without.forEach((event, content) -> assertThatThrownBy(() -> audit.add(sensitive(TENANT,
                        Event.valueOf(event.toUpperCase(java.util.Locale.ROOT))), content)).as(event)
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("trust_audit_records_sensitive_keeps_its_hmac"));
        assertThatThrownBy(() -> audit.add(sensitive(TENANT, Event.MODEL_CALLED), new Content("A question", "An answer")))
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("trust_audit_records_sensitive_keeps_its_hmac");
        String hex = "0".repeat(64);
        String call = "INSERT INTO trust_audit_records (record_id, tenant_id, application_id, end_user_id, conv_id, message_id,"
                + " task, event, data_class, model, prompt, question_hmac, key_id) VALUES ('aud_x', 'ten_a', 'app_a', 'eu_a', 'conv_a',"
                + " 'msg_a', 'customer_chat', 'model_called', '%s', 'fake', 'customer_chat/fake/v1', %s, %s)";
        Map<String, String> refusals = Map.of(
                call.formatted("public", "'" + hex + "'", "'tk_a'"), "trust_audit_records_hmacs_only_when_sensitive",
                call.formatted("sensitive", "'" + hex + "'", "NULL"), "trust_audit_records_key_with_its_hmacs",
                call.formatted("public", "NULL", "'tk_a'"), "trust_audit_records_key_with_its_hmacs",
                call.formatted("sensitive", "'" + "0".repeat(63) + "X'", "'tk_a'"), "trust_audit_records_question_hmac_check");
        refusals.forEach((sql, check) -> assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> tx.update(sql))).as(sql)
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("violates check constraint \"" + check + "\""));
        assertThat(hmacs()).isEmpty();
    }

    /**
     * M3-5-3: the database keeps a tenant's key wrapped, 80 characters of base64, and never in the clear, and the master
     * key it names is 16 hex digits; engine_app may read and add tenant keys and nothing more, so it can neither change
     * one nor destroy one.
     */
    @Test
    void aTenantKeyIsKeptWrappedAndCannotBeChangedOrDeleted() throws Exception {
        audit.add(sensitive(TENANT, Event.MODEL_CALLED), new Content("Hi", null));
        byte[] key = TenantKeyProbe.key(DATABASE, TENANT);
        String hex = HexFormat.of().formatHex(key);
        String base64 = java.util.Base64.getEncoder().encodeToString(key);
        List<String> before = TestDatabase.firstColumn(DATABASE, "SELECT concat_ws(' ', k.*) FROM engine.trust_tenant_keys k");

        assertThat(key).hasSize(32);
        assertThat(before).singleElement().asString().doesNotContain(hex, base64.substring(0, 40));
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT length(wrapped) FROM engine.trust_tenant_keys")).containsExactly("80");
        for (String sql : List.of("UPDATE trust_tenant_keys SET wrapped = wrapped", "DELETE FROM trust_tenant_keys",
                "TRUNCATE trust_tenant_keys", "SELECT key_id FROM trust_tenant_keys FOR UPDATE")) {
            assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> sql.startsWith("SELECT")
                    ? tx.query(sql, row -> row.string("key_id")).size() : tx.update(sql)))
                    .as(sql).isInstanceOf(DatabaseException.class).hasStackTraceContaining("permission denied");
        }
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT concat_ws(' ', k.*) FROM engine.trust_tenant_keys k")).isEqualTo(before);
        String add = "INSERT INTO trust_tenant_keys (key_id, tenant_id, master_key_id, wrapped) VALUES ('tk_x', 'ten_a', '%s', '%s')";
        Map<String, String> unfit = Map.of(
                add.formatted("0123456789abcdef", "A".repeat(79)), "trust_tenant_keys_wrapped_check",
                add.formatted("0123456789abcdef", "A".repeat(78) + "=="), "trust_tenant_keys_wrapped_check",
                add.formatted("0123456789ABCDEF", "A".repeat(80)), "trust_tenant_keys_master_key_id_check",
                add.formatted("0123456789abcde", "A".repeat(80)), "trust_tenant_keys_master_key_id_check");
        unfit.forEach((sql, check) -> assertThatThrownBy(() -> pool.inTenantTx("ten_unfit", tx -> tx.update(sql.replace("'ten_a'", "'ten_unfit'"))))
                .as(sql).isInstanceOf(DatabaseException.class).hasStackTraceContaining("violates check constraint \"" + check + "\""));
    }

    /** M3-5-3: the master key is 32 bytes, as AES-256 needs. */
    @Test
    void theMasterKeyIsThirtyTwoBytes() {
        for (byte[] wrong : java.util.Arrays.asList(null, new byte[31], new byte[33], new byte[0])) {
            assertThatThrownBy(() -> new DatabaseAudit(pool, wrong)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The audit's master key holds 32 bytes");
        }
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
