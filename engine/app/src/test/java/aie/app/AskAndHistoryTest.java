package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Asked;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Role;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Asking and the history the model is shown, on the real scripts, as engine_app. An ask stores the question and starts
 * its answer in one transaction, and asks in one conversation take turns, so each question comes right before its own
 * answer. The history of an answer is the conversation before it, without the answers that failed, are still in
 * progress or have no text, and at most {@link Messages#MAX_HISTORY} messages from a user message on. The tests that
 * wait on other threads stop after a minute at most.
 */
@Testcontainers
class AskAndHistoryTest {

    /** A character outside the Basic Multilingual Plane: one character, two UTF-16 units, four bytes. */
    private static final String ASTRAL = "😀";
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

    private static Asked ask(Owner caller, String conversation, String question) {
        return messages.ask(caller, conversation, question).orElseThrow();
    }

    /** Asks, gives the answer these deltas, and ends it as told; IN_PROGRESS leaves it going. */
    private static Asked exchange(Owner caller, String conversation, String question, Status ending, String... deltas) {
        Asked asked = ask(caller, conversation, question);
        String answer = asked.answer().messageId();
        for (String delta : deltas) {
            messages.addDelta(caller, conversation, answer, delta);
        }
        switch (ending) {
            case COMPLETED -> messages.complete(caller, conversation, answer, USAGE);
            case CANCELLED -> messages.cancel(caller, conversation, answer, USAGE);
            case FAILED -> messages.fail(caller, conversation, answer, ErrorCode.MODEL_FAILED);
            case IN_PROGRESS -> {
            }
        }
        return asked;
    }

    /** Each message as its role and its text, such as "user Hello?", so that a history reads in one line. */
    private static List<String> said(List<Message> history) {
        return history.stream().map(message -> message.role().name().toLowerCase(Locale.ROOT) + " " + message.text()).toList();
    }

    private static List<String> historyOf(Owner caller, String conversation, Asked asked) {
        return said(messages.historyFor(caller, conversation, asked.answer().messageId()));
    }

    /** What is really in the table, whatever row-level security lets the engine see. */
    private static List<String> rows() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT conversation_id || ' ' || role || ' ' || status || ' ' || text
                FROM engine.conversation_messages ORDER BY ordinal
                """);
    }

    /** The last number the messages' identity column gave, which moves as soon as a row takes one, committed or not. */
    private static String lastOrdinal() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT coalesce(pg_sequence_last_value(
                    pg_get_serial_sequence('engine.conversation_messages', 'ordinal')::regclass), 0)::text
                """).getFirst();
    }

    @Test
    void anAskStoresTheQuestionAndStartsItsAnswer() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        Asked asked = ask(caller, conversation, "Hello?");

        Message question = asked.question();
        assertThat(question.messageId()).matches("msg_[0-9A-Z]{26}");
        assertThat(question.conversationId()).isEqualTo(conversation);
        assertThat(question.role()).isEqualTo(Role.USER);
        assertThat(question.status()).isEqualTo(Status.COMPLETED);
        assertThat(question.text()).isEqualTo("Hello?");
        assertThat(question.completedAt()).isNotNull();
        Message answer = asked.answer();
        assertThat(answer.messageId()).matches("msg_[0-9A-Z]{26}").isNotEqualTo(question.messageId());
        assertThat(answer.conversationId()).isEqualTo(conversation);
        assertThat(answer.role()).isEqualTo(Role.ASSISTANT);
        assertThat(answer.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(answer.text()).isEmpty();
        assertThat(answer.usage()).isNull();
        assertThat(answer.completedAt()).isNull();
        assertThat(messages.list(caller, conversation)).containsExactly(question, answer);
        List<Event> events = messages.eventsAfter(caller, conversation, answer.messageId(), 0, 10);
        assertThat(events).extracting(Event::seq, Event::type).containsExactly(tuple(1L, EventType.MESSAGE_STARTED));
        assertThat(rows()).containsExactly(
                conversation + " user completed Hello?", conversation + " assistant in_progress ");
    }

    @Test
    void eachQuestionComesRightBeforeItsAnswer() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        Asked first = ask(caller, conversation, "One?");
        Asked second = ask(caller, conversation, "Two?");

        assertThat(messages.list(caller, conversation)).extracting(Message::messageId).containsExactly(
                first.question().messageId(), first.answer().messageId(),
                second.question().messageId(), second.answer().messageId());
    }

    /**
     * Without turns, another ask's row could take its number between a question and its answer. Eight threads ask at
     * once, ten times each, and every question still has its own answer right after it.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void asksAtOnceInOneConversationTakeTurns() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        int writers = 8;
        int asksEach = 10;
        Map<String, String> answerOf = new ConcurrentHashMap<>();
        CountDownLatch ready = new CountDownLatch(writers);
        ExecutorService threads = Executors.newFixedThreadPool(writers);
        List<CompletableFuture<Void>> asking = new ArrayList<>();
        for (int writer = 0; writer < writers; writer++) {
            int number = writer;
            asking.add(CompletableFuture.runAsync(() -> {
                ready.countDown();
                await(ready);
                for (int i = 0; i < asksEach; i++) {
                    Asked asked = ask(caller, conversation, "writer " + number + " ask " + i);
                    answerOf.put(asked.question().messageId(), asked.answer().messageId());
                }
            }, threads));
        }
        CompletableFuture.allOf(asking.toArray(CompletableFuture[]::new)).get(50, TimeUnit.SECONDS);
        threads.shutdown();

        List<Message> listed = messages.list(caller, conversation);
        assertThat(listed).hasSize(2 * writers * asksEach);
        for (int i = 0; i < listed.size(); i += 2) {
            assertThat(listed.get(i).role()).isEqualTo(Role.USER);
            assertThat(listed.get(i + 1).messageId()).isEqualTo(answerOf.get(listed.get(i).messageId()));
        }
    }

    /**
     * An ask first waits for the conversation's turn, before any row takes a number. The test holds a share lock on the
     * conversation, which a new message's foreign key would pass, so only the turn itself makes the ask wait.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anAskWaitsForTheConversationsTurnBeforeItStoresAnything() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Integer> holder = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            int held = tx.query("SELECT conversation_id FROM conversation_conversations WHERE conversation_id = ? FOR SHARE",
                    row -> row.string("conversation_id"), conversation).size();
            holding.countDown();
            await(release);
            return held;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
        String numberedBefore = lastOrdinal();

        CompletableFuture<Asked> asking = CompletableFuture.supplyAsync(() -> ask(caller, conversation, "Waiting?"), threads);
        waitForAWriterOnALock();
        assertThat(asking).isNotDone();
        assertThat(lastOrdinal()).isEqualTo(numberedBefore);

        release.countDown();

        assertThat(holder.get(30, TimeUnit.SECONDS)).isOne();
        Asked asked = asking.get(30, TimeUnit.SECONDS);
        assertThat(messages.list(caller, conversation)).containsExactly(asked.question(), asked.answer());
        threads.shutdown();
    }

    @Test
    void anAskOutOfReachStoresNothing() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        assertThat(messages.ask(caller, "conv_00000000000000000000000000", "Hello?")).isEmpty();
        assertThat(messages.ask(otherApplication, conversation, "Hello?")).isEmpty();
        assertThat(messages.ask(otherTenant, conversation, "Hello?")).isEmpty();
        assertThat(rows()).isEmpty();
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*)::text FROM engine.conversation_events")).containsExactly("0");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "\0", "Hello\0there"})
    void anAskNeedsTextTheDatabaseCanStore(String text) throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        assertThatThrownBy(() -> messages.ask(caller, conversation, text)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isEmpty();
    }

    /** The contract counts characters, as JSON Schema does, so a character outside the BMP counts once. */
    @Test
    void anAskTakesUpTo32000Characters() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        for (String text : List.of("a".repeat(32001), ASTRAL.repeat(32001))) {
            assertThatThrownBy(() -> messages.ask(caller, conversation, text))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("A user message must have 1 to 32000 characters");
        }
        assertThat(rows()).isEmpty();

        assertThat(ask(caller, conversation, ASTRAL.repeat(32000)).question().text()).isEqualTo(ASTRAL.repeat(32000));
    }

    /** A failure partway leaves nothing behind: neither a question without an answer, nor an answer without its start. */
    @Test
    void anAskIsOneTransaction() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        TestDatabase.asAdmin(DATABASE, """
                CREATE FUNCTION engine.test_refuse_start() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'The test refuses message.started';
                END
                $$;
                CREATE TRIGGER test_refuse_start BEFORE INSERT ON engine.conversation_events
                    FOR EACH ROW WHEN (NEW.type = 'message.started') EXECUTE FUNCTION engine.test_refuse_start();
                """);
        try {
            assertThatThrownBy(() -> messages.ask(caller, conversation, "Hello?"))
                    .isInstanceOf(DatabaseException.class).hasStackTraceContaining("The test refuses message.started");
            assertThat(rows()).isEmpty();
        } finally {
            TestDatabase.asAdmin(DATABASE, """
                    DROP TRIGGER test_refuse_start ON engine.conversation_events;
                    DROP FUNCTION engine.test_refuse_start();
                    """);
        }
        assertThat(ask(caller, conversation, "Hello?").answer().status()).isEqualTo(Status.IN_PROGRESS);
    }

    @Test
    void theHistoryLeavesOutWhatTheEndUserDidNotGetAsAnAnswer() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        exchange(caller, conversation, "Hi", Status.COMPLETED, "Hel", "lo!");
        exchange(caller, conversation, "Weather?", Status.FAILED, "Sun");
        exchange(caller, conversation, "Tell me more", Status.CANCELLED, "It ", "is");
        exchange(caller, conversation, "Anything?", Status.CANCELLED);
        exchange(caller, conversation, "Again?", Status.COMPLETED);
        exchange(caller, conversation, "Still there?", Status.IN_PROGRESS, "Thinking");
        Asked last = ask(caller, conversation, "Last one");
        exchange(caller, conversation, "Later", Status.COMPLETED, "After");

        List<Message> history = messages.historyFor(caller, conversation, last.answer().messageId());

        assertThat(said(history)).containsExactly("user Hi", "assistant Hello!", "user Weather?", "user Tell me more",
                "assistant It is", "user Anything?", "user Again?", "user Still there?", "user Last one");
        assertThat(history).extracting(Message::status).containsOnly(Status.COMPLETED, Status.CANCELLED);
        assertThat(history.get(1).usage()).isEqualTo(USAGE);
        assertThat(history.getLast()).isEqualTo(last.question());
    }

    @Test
    void theHistoryOfAnAnswerEndsWithItsQuestionEvenAfterTheAnswerEnded() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        Asked first = exchange(caller, conversation, "One?", Status.COMPLETED, "One.");
        Asked second = exchange(caller, conversation, "Two?", Status.COMPLETED, "Two.");

        assertThat(historyOf(caller, conversation, first)).containsExactly("user One?");
        assertThat(historyOf(caller, conversation, second)).containsExactly("user One?", "assistant One.", "user Two?");
    }

    /**
     * Two answers failed right before the 50 that count, so the 50th and the 51st from the end are both questions: one
     * message more or less would show.
     */
    @Test
    void theHistoryHoldsTheLast50MessagesThatCount() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        exchange(caller, conversation, "q1", Status.FAILED);
        exchange(caller, conversation, "q2", Status.FAILED);
        for (int i = 3; i <= 26; i++) {
            exchange(caller, conversation, "q" + i, Status.COMPLETED, "a" + i);
        }
        exchange(caller, conversation, "q27", Status.FAILED);
        Asked last = ask(caller, conversation, "q28");

        List<String> history = historyOf(caller, conversation, last);

        assertThat(history).hasSize(Messages.MAX_HISTORY).startsWith("user q3", "assistant a3")
                .endsWith("assistant a26", "user q27", "user q28").doesNotContain("user q2");
    }

    /** The last 50 that count would start with the answer a6, so the history leaves it out and starts with q7. */
    @Test
    void theHistoryStartsWithAQuestion() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        for (int i = 1; i <= 30; i++) {
            exchange(caller, conversation, "q" + i, Status.COMPLETED, "a" + i);
        }
        Asked last = ask(caller, conversation, "q31");

        List<String> history = historyOf(caller, conversation, last);

        assertThat(history).hasSize(Messages.MAX_HISTORY - 1).startsWith("user q7", "assistant a7").endsWith("assistant a30", "user q31");
    }

    @Test
    void theHistoryStaysInItsOwnConversation() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String other = newConversation(caller);
        exchange(caller, other, "Elsewhere", Status.COMPLETED, "There.");
        exchange(caller, conversation, "Here", Status.COMPLETED, "Here.");
        exchange(caller, other, "Elsewhere again", Status.COMPLETED, "There again.");
        Asked last = ask(caller, conversation, "Here again");

        assertThat(historyOf(caller, conversation, last)).containsExactly("user Here", "assistant Here.", "user Here again");
    }

    @Test
    void onlyAnAnswerInReachHasAHistory() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        exchange(caller, conversation, "Hi", Status.COMPLETED, "Hello!");
        Asked last = ask(caller, conversation, "And?");
        String answer = last.answer().messageId();
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        assertThat(messages.historyFor(caller, conversation, answer)).hasSize(3);
        assertThat(messages.historyFor(caller, conversation, last.question().messageId())).isEmpty();
        assertThat(messages.historyFor(caller, conversation, "msg_00000000000000000000000000")).isEmpty();
        assertThat(messages.historyFor(caller, newConversation(caller), answer)).isEmpty();
        assertThat(messages.historyFor(otherApplication, conversation, answer)).isEmpty();
        assertThat(messages.historyFor(otherTenant, conversation, answer)).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("A latch was not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
        throw new AssertionError("No ask came to wait on a lock");
    }
}
