package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.api.Messages.Written;
import aie.modules.conversation.api.Messages.Written.Added;
import aie.modules.conversation.api.Messages.Written.AlreadyEnded;
import aie.modules.conversation.api.Messages.Written.NotFound;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * How answers end, on the real scripts, as engine_app: each ending event writes its result into the answer, the answer
 * ends at most once, and after its ending it takes nothing more. Of two writers that end an answer at once, one wins and
 * the other learns that the answer has ended. The tests that wait on other threads stop after a minute at most.
 */
@Testcontainers
class AnswerEndingsTest {

    private static final String ADD_EVENT = """
            INSERT INTO conversation_events (tenant_id, message_id, type, text, input_tokens, output_tokens, code)
            VALUES (?, ?, ?, ?::text, ?::bigint, ?::bigint, ?::text) RETURNING seq
            """;
    private static final Usage USAGE = new Usage(12, 34);

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
    private static Messages messages;
    private static Conversations conversations;
    private static EndUsers endUsers;
    private static Provisioning provisioning;

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        database = new PgDatabase(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD), Duration.ofSeconds(30));
        messages = new DatabaseMessages(database);
        conversations = new DatabaseConversations(database);
        endUsers = new DatabaseEndUsers(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.conversation_conversations CASCADE");
    }

    /** A caller of a new tenant's application; the key ID plays no part here. */
    private static Owner newApplication() {
        String tenant = provisioning.createTenant("jp1", null);
        return new Owner("key_unused", tenant, provisioning.createApplication(tenant, "test", "internal"));
    }

    private static String newConversation(Owner caller) {
        return conversations.start(caller, endUsers.resolve(caller, "user-1"), null).conversationId();
    }

    private static String newAnswer(Owner caller, String conversation) {
        return messages.startAssistantMessage(caller, conversation).orElseThrow().messageId();
    }

    private static Message found(Owner caller, String conversation, String answer) {
        return messages.find(caller, conversation, answer).orElseThrow();
    }

    private static List<Event> events(Owner caller, String conversation, String answer) {
        return messages.eventsAfter(caller, conversation, answer, 0, 1000);
    }

    /** Adds an event as engine_app with SQL of its own, going around DatabaseMessages, and returns its number. */
    private static long addRow(Owner caller, String answer, String type, String text, Long input, Long output, String code) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(
                ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, type, text, input, output, code).getFirst());
    }

    private static void assertRefused(Runnable write, String reason) {
        assertThatThrownBy(write::run).isInstanceOf(DatabaseException.class).hasStackTraceContaining(reason);
    }

    /** What is really in the log, whatever row-level security lets the engine see. */
    private static List<String> endings() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT message_id || ' ' || seq || ' ' || type FROM engine.conversation_events
                WHERE type IN ('message.completed', 'message.cancelled', 'error') ORDER BY message_id
                """);
    }

    @Test
    void aCompletedAnswerKeepsItsTextAndUsage() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "Hel");
        messages.addDelta(caller, conversation, answer, "lo");

        assertThat(messages.complete(caller, conversation, answer, USAGE)).isEqualTo(new Added(4));

        Message ended = found(caller, conversation, answer);
        assertThat(ended.status()).isEqualTo(Status.COMPLETED);
        assertThat(ended.text()).isEqualTo("Hello");
        assertThat(ended.usage()).isEqualTo(USAGE);
        assertThat(ended.code()).isNull();
        assertThat(ended.completedAt()).isAfterOrEqualTo(ended.createdAt());
        assertThat(messages.list(caller, conversation)).containsExactly(ended);
        Event last = events(caller, conversation, answer).getLast();
        assertThat(last.seq()).isEqualTo(4);
        assertThat(last.type()).isEqualTo(EventType.MESSAGE_COMPLETED);
        assertThat(last.type().wireName()).isEqualTo("message.completed");
        assertThat(last.usage()).isEqualTo(USAGE);
        assertThat(last.text()).isNull();
        assertThat(last.code()).isNull();
    }

    @Test
    void aCancelledAnswerCarriesWhatWasGenerated() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "Hal");
        messages.addDelta(caller, conversation, answer, "f");

        assertThat(messages.cancel(caller, conversation, answer, new Usage(5, 2))).isEqualTo(new Added(4));

        Message ended = found(caller, conversation, answer);
        assertThat(ended.status()).isEqualTo(Status.CANCELLED);
        assertThat(ended.text()).isEqualTo("Half");
        assertThat(ended.usage()).isEqualTo(new Usage(5, 2));
        assertThat(ended.code()).isNull();
        assertThat(ended.completedAt()).isNotNull();
        Event last = events(caller, conversation, answer).getLast();
        assertThat(last.type()).isEqualTo(EventType.MESSAGE_CANCELLED);
        assertThat(last.type().wireName()).isEqualTo("message.cancelled");
        assertThat(last.text()).isEqualTo("Half");
        assertThat(last.usage()).isEqualTo(new Usage(5, 2));
    }

    @Test
    void anAnswerCancelledBeforeItsFirstDeltaCarriesNoText() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThat(messages.cancel(caller, conversation, answer, new Usage(5, 0))).isEqualTo(new Added(2));

        assertThat(found(caller, conversation, answer).text()).isEmpty();
        assertThat(events(caller, conversation, answer).getLast().text()).isEmpty();
    }

    @Test
    void aFailedAnswerKeepsItsCodeAndWhatItGenerated() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "Par");
        String interrupted = newAnswer(caller, conversation);

        assertThat(messages.fail(caller, conversation, answer, ErrorCode.MODEL_FAILED)).isEqualTo(new Added(3));
        assertThat(messages.fail(caller, conversation, interrupted, ErrorCode.STREAM_INTERRUPTED)).isEqualTo(new Added(2));

        Message failed = found(caller, conversation, answer);
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.text()).isEqualTo("Par");
        assertThat(failed.usage()).isNull();
        assertThat(failed.code()).isEqualTo(ErrorCode.MODEL_FAILED);
        assertThat(failed.completedAt()).isNotNull();
        assertThat(found(caller, conversation, interrupted).code()).isEqualTo(ErrorCode.STREAM_INTERRUPTED);
        Event last = events(caller, conversation, answer).getLast();
        assertThat(last.type()).isEqualTo(EventType.ERROR);
        assertThat(last.type().wireName()).isEqualTo("error");
        assertThat(last.code()).isEqualTo(ErrorCode.MODEL_FAILED);
        assertThat(last.code().wireName()).isEqualTo("model_failed");
        assertThat(last.usage()).isNull();
        assertThat(last.text()).isNull();
        assertThat(ErrorCode.STREAM_INTERRUPTED.wireName()).isEqualTo("stream_interrupted");
    }

    /** Whatever comes after the ending, the answer stays as it ended. */
    @Test
    void anEndedAnswerTakesNothingMore() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "Done");
        messages.complete(caller, conversation, answer, USAGE);
        Message ended = found(caller, conversation, answer);

        AlreadyEnded over = new AlreadyEnded(Status.COMPLETED);
        assertThat(messages.addDelta(caller, conversation, answer, "more")).isEqualTo(over);
        assertThat(messages.complete(caller, conversation, answer, new Usage(1, 1))).isEqualTo(over);
        assertThat(messages.cancel(caller, conversation, answer, new Usage(1, 1))).isEqualTo(over);
        assertThat(messages.fail(caller, conversation, answer, ErrorCode.STREAM_INTERRUPTED)).isEqualTo(over);

        assertThat(found(caller, conversation, answer)).isEqualTo(ended);
        assertThat(events(caller, conversation, answer)).extracting(Event::seq).containsExactly(1L, 2L, 3L);
        assertThat(endings()).containsExactly(answer + " 3 message.completed");
    }

    /** SQL that goes around DatabaseMessages is refused by the database itself, under the answer's lock. */
    @Test
    void theDatabaseRefusesAnyEventAfterTheEnd() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.fail(caller, conversation, answer, ErrorCode.MODEL_FAILED);

        assertRefused(() -> addRow(caller, answer, "message.delta", "late", null, null, null), "has ended, so it takes no more events");
        assertRefused(() -> addRow(caller, answer, "message.completed", null, 1L, 1L, null), "has ended, so it takes no more events");
        assertRefused(() -> addRow(caller, answer, "error", null, null, null, "stream_interrupted"),
                "has ended, so it takes no more events");
    }

    /** The grant to update messages serves the database's own ending; engine_app still cannot change a message. */
    @Test
    void anEndedAnswerCannotBeChangedByHand() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.complete(caller, conversation, answer, USAGE);

        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_messages SET status = 'in_progress', completed_at = NULL, input_tokens = NULL,"
                        + " output_tokens = NULL WHERE message_id = ?", answer)), "A message changes only through its events");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_messages SET text = 'other' WHERE message_id = ?", answer)), "A message changes only through its events");
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'completed', 1, 1, NULL, now()",
            "'cancelled', 1, 1, NULL, now()",
            "'failed', NULL, NULL, 'model_failed', now()",
            "'blocked', NULL, NULL, NULL, now()"})
    void anAnswerCannotBeStoredAsEnded(String values) throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("""
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, input_tokens,
                    output_tokens, code, completed_at)
                VALUES ('msg_ended', ?, ?, 'assistant', %s)
                """.formatted(values), caller.tenantId(), conversation)), "An answer starts in progress");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_messages")).isEmpty();
    }

    /**
     * Two writers end an answer at once, again and again. Each time one of them wins and the other learns how the answer
     * ended, and the answer has exactly one ending event.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void ofTwoWritersThatEndAnAnswerAtOnceOneWins() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String answer = newAnswer(caller, conversation);
                CountDownLatch ready = new CountDownLatch(2);
                CompletableFuture<Written> completing = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(ready);
                    return messages.complete(caller, conversation, answer, USAGE);
                }, threads);
                CompletableFuture<Written> interrupting = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(ready);
                    return messages.fail(caller, conversation, answer, ErrorCode.STREAM_INTERRUPTED);
                }, threads);
                Written completed = completing.get(30, TimeUnit.SECONDS);
                Written interrupted = interrupting.get(30, TimeUnit.SECONDS);

                Status ending = found(caller, conversation, answer).status();
                if (completed instanceof Added) {
                    assertThat(ending).isEqualTo(Status.COMPLETED);
                    assertThat(interrupted).isEqualTo(new AlreadyEnded(Status.COMPLETED));
                } else {
                    assertThat(ending).isEqualTo(Status.FAILED);
                    assertThat(interrupted).isInstanceOf(Added.class);
                    assertThat(completed).isEqualTo(new AlreadyEnded(Status.FAILED));
                }
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(endings()).hasSize(20);
        assertThat(TestDatabase.firstColumn(DATABASE, """
                SELECT count(*)::text FROM engine.conversation_events
                WHERE type IN ('message.completed', 'message.cancelled', 'error') GROUP BY message_id HAVING count(*) > 1
                """)).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The first writer holds its ending open. The second has already looked at the answer and found it in progress, so it
     * waits for the answer's lock; once the first commits, the database refuses the second's event, and the second learns
     * how the answer ended instead of meeting a database error.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aWriterThatWaitedForTheLockLearnsHowTheAnswerEnded() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Long> first = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            long number = tx.query(ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, "message.completed",
                    null, 7L, 8L, null).getFirst();
            holding.countDown();
            await(release);
            return number;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Written> second = CompletableFuture.supplyAsync(
                () -> messages.fail(caller, conversation, answer, ErrorCode.STREAM_INTERRUPTED), threads);
        waitForAWriterOnALock();
        assertThat(second).isNotDone();

        release.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(new AlreadyEnded(Status.COMPLETED));
        assertThat(found(caller, conversation, answer).usage()).isEqualTo(new Usage(7, 8));
        assertThat(endings()).containsExactly(answer + " 2 message.completed");
        threads.shutdown();
    }

    private static void waitForAWriterOnALock() throws SQLException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            List<String> waiting = TestDatabase.firstColumn(DATABASE, """
                    SELECT count(*)::text FROM pg_stat_activity
                    WHERE usename = 'engine_app' AND wait_event_type = 'Lock'
                    """);
            if (!waiting.getFirst().equals("0")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No writer came to wait on a lock");
    }

    /**
     * M2-7-4: each event that goes in rings its answer's signal for the readers of this instance, after its transaction
     * has committed. An event that does not go in rings nothing.
     */
    @Test
    void eachEventThatGoesInRingsItsAnswerAndARefusedOneDoesNot() {
        List<String> rung = new java.util.concurrent.CopyOnWriteArrayList<>();
        EventSignals recording = new EventSignals() {

            @Override
            public java.util.concurrent.CompletableFuture<Void> next(String messageId) {
                return new java.util.concurrent.CompletableFuture<>();
            }

            @Override
            public void ring(String messageId) {
                rung.add(messageId);
            }

            @Override
            public void forget(String messageId, java.util.concurrent.CompletableFuture<Void> signal) {
            }
        };
        Messages ringing = new DatabaseMessages(database, recording);
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThat(ringing.addDelta(caller, conversation, answer, "Hi")).isEqualTo(new Added(2));
        assertThat(ringing.complete(caller, conversation, answer, USAGE)).isEqualTo(new Added(3));
        assertThat(ringing.addDelta(caller, conversation, answer, "late")).isEqualTo(new AlreadyEnded(Status.COMPLETED));
        assertThat(ringing.fail(caller, conversation, "msg_00000000000000000000000000", ErrorCode.MODEL_FAILED))
                .isEqualTo(new NotFound());

        assertThat(rung).containsExactly(answer, answer);
    }

    @Test
    void anAnswerOutOfReachIsNotFound() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        NotFound none = new NotFound();
        assertThat(messages.complete(caller, conversation, question, USAGE)).isEqualTo(none);
        assertThat(messages.cancel(caller, conversation, "msg_00000000000000000000000000", USAGE)).isEqualTo(none);
        assertThat(messages.fail(caller, newConversation(caller), answer, ErrorCode.MODEL_FAILED)).isEqualTo(none);
        assertThat(messages.complete(otherApplication, conversation, answer, USAGE)).isEqualTo(none);
        assertThat(messages.fail(otherTenant, conversation, answer, ErrorCode.MODEL_FAILED)).isEqualTo(none);
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
    }

    @Test
    void anEndingNeedsItsUsageOrCode() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThatThrownBy(() -> new Usage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.complete(caller, conversation, answer, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.cancel(caller, conversation, answer, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.fail(caller, conversation, answer, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Usage(0, 0).inputTokens()).isZero();
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
    }

    /** SQL that goes around DatabaseMessages meets the same rules of what each ending carries. */
    @Test
    void theLogKeepsWhatEachEndingCarries() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "Half");

        assertRefused(() -> addRow(caller, answer, "message.completed", null, null, null, null), "conversation_events_usage");
        assertRefused(() -> addRow(caller, answer, "message.completed", null, 1L, null, null), "conversation_events_usage");
        assertRefused(() -> addRow(caller, answer, "message.completed", null, -1L, 1L, null), "conversation_events_usage");
        assertRefused(() -> addRow(caller, answer, "message.completed", "text", 1L, 1L, null), "conversation_events_text");
        assertRefused(() -> addRow(caller, answer, "message.completed", null, 1L, 1L, "model_failed"), "conversation_events_code");
        assertRefused(() -> addRow(caller, answer, "message.delta", "more", 1L, 1L, null), "conversation_events_usage");
        assertRefused(() -> addRow(caller, answer, "message.delta", "more", null, null, "model_failed"), "conversation_events_code");
        assertRefused(() -> addRow(caller, answer, "error", null, null, null, null), "conversation_events_code");
        assertRefused(() -> addRow(caller, answer, "error", null, null, null, "output_blocked"), "conversation_events_code");
        assertRefused(() -> addRow(caller, answer, "error", null, 1L, 1L, "model_failed"), "conversation_events_usage");
        assertRefused(() -> addRow(caller, answer, "error", "text", null, null, "model_failed"), "conversation_events_text");
        assertRefused(() -> addRow(caller, answer, "message.cancelled", null, null, null, null), "conversation_events_usage");
        assertRefused(() -> addRow(caller, answer, "message.cancelled", "Whole", 1L, 1L, null), "and that is not it");

        assertThat(addRow(caller, answer, "message.cancelled", "Half", 1L, 1L, null)).isEqualTo(3);
        assertThat(found(caller, conversation, answer).text()).isEqualTo("Half");
    }

    /**
     * The message keeps the rule of an ended answer too. Only the database's own ending writes one, so the superuser
     * tries the wrong ones with the triggers off.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'completed', completed_at = now()",
            "status = 'completed', completed_at = now(), input_tokens = 1",
            "status = 'completed', completed_at = now(), input_tokens = 1, output_tokens = 1, code = 'model_failed'",
            "status = 'cancelled', completed_at = now()",
            "status = 'failed', completed_at = now()",
            "status = 'failed', completed_at = now(), code = 'output_blocked'",
            "status = 'failed', completed_at = now(), code = 'model_failed', input_tokens = 1, output_tokens = 1",
            "status = 'in_progress', text = 'Hi'"})
    void theMessageTableKeepsAnEndedAnswerWhole(String change) throws SQLException {
        Owner caller = newApplication();
        String answer = newAnswer(caller, newConversation(caller));

        assertThatThrownBy(() -> TestDatabase.asAdmin(DATABASE, "SET session_replication_role = replica; "
                + "UPDATE engine.conversation_messages SET " + change + " WHERE message_id = '" + answer + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("conversation_messages_answer");
    }

    @Test
    void theMessageTableTakesAnAnswerThatEndedRight() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String completed = newAnswer(caller, conversation);
        String failed = newAnswer(caller, conversation);

        TestDatabase.asAdmin(DATABASE, "SET session_replication_role = replica; UPDATE engine.conversation_messages"
                + " SET status = 'completed', completed_at = now(), input_tokens = 0, output_tokens = 0, text = 'x'"
                + " WHERE message_id = '" + completed + "'");
        TestDatabase.asAdmin(DATABASE, "SET session_replication_role = replica; UPDATE engine.conversation_messages"
                + " SET status = 'failed', completed_at = now(), code = 'stream_interrupted' WHERE message_id = '" + failed + "'");

        assertThat(found(caller, conversation, completed).status()).isEqualTo(Status.COMPLETED);
        assertThat(found(caller, conversation, failed).status()).isEqualTo(Status.FAILED);
    }
}
