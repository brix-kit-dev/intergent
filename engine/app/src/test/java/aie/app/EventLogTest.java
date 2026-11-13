package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Written;
import aie.modules.conversation.api.Messages.Written.Added;
import aie.modules.conversation.api.Messages.Written.NotFound;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
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
 * The event log of answers on the real scripts, as engine_app: the database numbers the events of each answer one by
 * one, with no gap and in the order they are committed, an answer starts with message.started, and only the database
 * changes a message. The tests that wait on other threads stop after a minute at most, so a lost lock cannot hang the
 * build.
 */
@Testcontainers
class EventLogTest {

    private static final String ADD_EVENT = """
            INSERT INTO conversation_events (tenant_id, message_id, type, text) VALUES (?, ?, ?, ?) RETURNING seq
            """;

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

    /** Each event as "number type text". */
    private static List<String> described(List<Event> events) {
        return events.stream().map(event -> event.seq() + " " + event.type().wireName() + " " + event.text()).toList();
    }

    private static List<String> eventsOf(Owner caller, String conversation, String answer) {
        return described(messages.eventsAfter(caller, conversation, answer, 0, 1000));
    }

    /** What is really in the log, whatever row-level security lets the engine see. */
    private static List<String> rows() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT message_id || ' ' || seq || ' ' || type || ' ' || coalesce(text, '-')
                FROM engine.conversation_events ORDER BY message_id, seq
                """);
    }

    /** Adds an event as engine_app with SQL of its own, going around DatabaseMessages, and returns its number. */
    private static long addRow(Owner caller, String messageId, String type, String text) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(
                ADD_EVENT, row -> row.number("seq"), caller.tenantId(), messageId, type, text).getFirst());
    }

    private static void assertRefused(Runnable write, String reason) {
        assertThatThrownBy(write::run).isInstanceOf(DatabaseException.class).hasStackTraceContaining(reason);
    }

    @Test
    void anAnswerStartsWithMessageStartedAsNumberOne() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        String answer = newAnswer(caller, conversation);

        List<Event> events = messages.eventsAfter(caller, conversation, answer, 0, 10);
        assertThat(events).hasSize(1);
        Event started = events.getFirst();
        assertThat(started.messageId()).isEqualTo(answer);
        assertThat(started.seq()).isOne();
        assertThat(started.type()).isEqualTo(EventType.MESSAGE_STARTED);
        assertThat(started.type().wireName()).isEqualTo("message.started");
        assertThat(started.text()).isNull();
        assertThat(started.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void deltasTakeTheNextNumbersOneByOne() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThat(messages.addDelta(caller, conversation, answer, "Hel")).isEqualTo(new Added(2));
        assertThat(messages.addDelta(caller, conversation, answer, "lo")).isEqualTo(new Added(3));
        assertThat(messages.addDelta(caller, conversation, answer, ", world")).isEqualTo(new Added(4));

        assertThat(eventsOf(caller, conversation, answer)).containsExactly(
                "1 message.started null", "2 message.delta Hel", "3 message.delta lo", "4 message.delta , world");
    }

    /** Each answer counts its own events, from 1. */
    @Test
    void eachAnswerHasNumbersOfItsOwn() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String first = newAnswer(caller, conversation);
        String second = newAnswer(caller, conversation);

        assertThat(messages.addDelta(caller, conversation, first, "a")).isEqualTo(new Added(2));
        assertThat(messages.addDelta(caller, conversation, second, "b")).isEqualTo(new Added(2));
        assertThat(messages.addDelta(caller, conversation, first, "c")).isEqualTo(new Added(3));

        assertThat(eventsOf(caller, conversation, first)).containsExactly(
                "1 message.started null", "2 message.delta a", "3 message.delta c");
        assertThat(eventsOf(caller, conversation, second)).containsExactly("1 message.started null", "2 message.delta b");
    }

    @Test
    void aReaderGetsTheEventsAfterTheNumberItHas() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "a");
        messages.addDelta(caller, conversation, answer, "b");
        messages.addDelta(caller, conversation, answer, "c");

        assertThat(described(messages.eventsAfter(caller, conversation, answer, 2, 10)))
                .containsExactly("3 message.delta b", "4 message.delta c");
        assertThat(described(messages.eventsAfter(caller, conversation, answer, 1, 1))).containsExactly("2 message.delta a");
        assertThat(described(messages.eventsAfter(caller, conversation, answer, 0, 2)))
                .containsExactly("1 message.started null", "2 message.delta a");
        assertThat(messages.eventsAfter(caller, conversation, answer, 4, 10)).isEmpty();
    }

    /**
     * The primary key holds an answer's events in order already, so the test moves one of them where they lie and turns
     * index scans off for engine_app: only ORDER BY can keep the order then, both for the events and for the text of the
     * answer. Then neither the order the rows lie in nor its reverse is the right one.
     */
    @Test
    void eventsAreReadInTheirOrderWhereverTheyLie() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "a");
        messages.addDelta(caller, conversation, answer, "b");
        messages.addDelta(caller, conversation, answer, "c");

        TestDatabase.asAdmin(DATABASE, "UPDATE engine.conversation_events SET text = text WHERE seq = 2");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT seq::text FROM engine.conversation_events"))
                .as("where the rows lie").containsExactly("1", "3", "4", "2");

        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app SET enable_indexscan = off");
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app SET enable_bitmapscan = off");
        try {
            assertThat(messages.eventsAfter(caller, conversation, answer, 0, 10)).extracting(Event::seq)
                    .containsExactly(1L, 2L, 3L, 4L);
            assertThat(messages.find(caller, conversation, answer))
                    .hasValueSatisfying(found -> assertThat(found.text()).isEqualTo("abc"));
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app RESET enable_indexscan");
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app RESET enable_bitmapscan");
        }
    }

    @Test
    void aReadNeedsANumberFromZeroAndALimitFromOne() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThatThrownBy(() -> messages.eventsAfter(caller, conversation, answer, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.eventsAfter(caller, conversation, answer, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTextOfAnAnswerInProgressIsItsDeltasSoFar() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        Message question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow();
        String answer = newAnswer(caller, conversation);
        String quiet = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "Hel");
        messages.addDelta(caller, conversation, answer, "lo");

        assertThat(messages.find(caller, conversation, answer)).hasValueSatisfying(found -> assertThat(found.text()).isEqualTo("Hello"));
        assertThat(messages.find(caller, conversation, quiet)).hasValueSatisfying(found -> assertThat(found.text()).isEmpty());
        assertThat(messages.list(caller, conversation)).extracting(Message::text).containsExactly("Hello?", "Hello", "");
        assertThat(messages.find(caller, conversation, question.messageId())).contains(question);
    }

    /** The number comes from the events inside the transaction, so a rollback takes it back too. */
    @Test
    void aRolledBackEventLeavesNoGap() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "kept");

        assertThatThrownBy(() -> database.inTenantTx(caller.tenantId(), tx -> {
            tx.query(ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, "message.delta", "rolled back");
            throw new IllegalStateException("roll it back");
        })).hasMessageContaining("roll it back");

        assertThat(messages.addDelta(caller, conversation, answer, "next")).isEqualTo(new Added(3));
        assertThat(eventsOf(caller, conversation, answer)).containsExactly(
                "1 message.started null", "2 message.delta kept", "3 message.delta next");
    }

    /** Writers at once wait for each other on the answer's lock, so every one gets a number and none gets one twice. */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void writersAtOnceTakeEveryNumberOnce() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        int writers = 8;
        int each = 25;
        CountDownLatch ready = new CountDownLatch(writers);
        ExecutorService threads = Executors.newFixedThreadPool(writers);
        try {
            List<Future<List<Long>>> taken = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                taken.add(threads.submit(() -> {
                    ready.countDown();
                    ready.await();
                    List<Long> numbers = new ArrayList<>();
                    for (int i = 0; i < each; i++) {
                        numbers.add(((Added) messages.addDelta(caller, conversation, answer, writer + ":" + i)).seq());
                    }
                    return numbers;
                }));
            }
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> numbers : taken) {
                assertThat(numbers.get()).as("one writer's numbers, in the order it wrote").isSorted();
                all.addAll(numbers.get());
            }
            assertThat(all).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(2, 1 + writers * each).boxed().toList());
        } finally {
            threads.shutdownNow();
        }
        assertThat(messages.eventsAfter(caller, conversation, answer, 0, 1000)).extracting(Event::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 1 + writers * each).boxed().toList());
    }

    /**
     * A writer holds number 2 without committing it yet. A second writer waits for the answer's lock instead of taking a
     * number of its own, so a reader that has number 1 sees nothing until the first commits, and then both, in order. A
     * number from a sequence would let the second commit 3 while 2 is still open, and the reader would read past 2.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aReaderNeverSkipsANumberThatIsCommittedLater() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Long> first = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            long number = tx.query(ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, "message.delta", "first")
                    .getFirst();
            holding.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return number;
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Written> second =
                CompletableFuture.supplyAsync(() -> messages.addDelta(caller, conversation, answer, "second"));
        waitForAWriterOnALock();
        assertThat(second).as("the second writer waits for the first").isNotDone();
        assertThat(messages.eventsAfter(caller, conversation, answer, 1, 10)).as("nothing is committed after 1 yet").isEmpty();

        release.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(new Added(3));
        assertThat(described(messages.eventsAfter(caller, conversation, answer, 1, 10)))
                .containsExactly("2 message.delta first", "3 message.delta second");
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

    @Test
    void onlyAnAnswerHasEvents() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();

        assertThat(messages.addDelta(caller, conversation, question, "no")).isEqualTo(new NotFound());
        assertThat(messages.eventsAfter(caller, conversation, question, 0, 10)).isEmpty();
        assertRefused(() -> addRow(caller, question, "message.started", null), "Only an answer has events");
        assertThat(rows()).isEmpty();
    }

    @Test
    void anAnswerIsReachedOnlyThroughItsConversationAndApplication() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        String otherConversation = newConversation(caller);
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        assertThat(messages.addDelta(caller, otherConversation, answer, "x")).isEqualTo(new NotFound());
        assertThat(messages.addDelta(otherApplication, conversation, answer, "x")).isEqualTo(new NotFound());
        assertThat(messages.addDelta(otherTenant, conversation, answer, "x")).isEqualTo(new NotFound());
        assertThat(messages.addDelta(caller, conversation, "msg_00000000000000000000000000", "x")).isEqualTo(new NotFound());
        assertThat(messages.eventsAfter(caller, otherConversation, answer, 0, 10)).isEmpty();
        assertThat(messages.eventsAfter(otherApplication, conversation, answer, 0, 10)).isEmpty();
        assertThat(messages.eventsAfter(otherTenant, conversation, answer, 0, 10)).isEmpty();
        assertThat(rows()).containsExactly(answer + " 1 message.started -");
    }

    @Test
    void theDatabaseGivesTheNumberWhateverTheWriterSays() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        int added = database.inTenantTx(caller.tenantId(), tx -> tx.update("""
                INSERT INTO conversation_events (tenant_id, message_id, seq, type, text) VALUES (?, ?, 99, 'message.delta', 'x')
                """, caller.tenantId(), answer));

        assertThat(added).isOne();
        assertThat(eventsOf(caller, conversation, answer)).containsExactly("1 message.started null", "2 message.delta x");
    }

    /** startAssistantMessage writes message.started itself; SQL that goes around it meets the same rule. */
    @Test
    void messageStartedComesFirstAndOnlyFirst() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        assertRefused(() -> addRow(caller, answer, "message.started", null), "conversation_events_started_first");

        String bare = "msg_bare";
        database.inTenantTx(caller.tenantId(), tx -> tx.update("""
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status) VALUES (?, ?, ?, 'assistant', 'in_progress')
                """, bare, caller.tenantId(), conversation));
        assertRefused(() -> addRow(caller, bare, "message.delta", "too soon"), "conversation_events_started_first");
        assertThat(addRow(caller, bare, "message.started", null)).isOne();
        assertThat(addRow(caller, bare, "message.delta", "now")).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "\0", "a\0b"})
    void aDeltaNeedsTextTheDatabaseCanStore(String text) throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThatThrownBy(() -> messages.addDelta(caller, conversation, answer, text)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).containsExactly(answer + " 1 message.started -");
    }

    @Test
    void theLogKeepsItsRulesOfTextAndType() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertRefused(() -> addRow(caller, answer, "message.delta", null), "conversation_events_text");
        assertRefused(() -> addRow(caller, answer, "message.delta", ""), "conversation_events_text");
        for (String later : List.of("message.blocked", "message.citation", "tool.started", "handoff.requested")) {
            assertRefused(() -> addRow(caller, answer, later, null), "conversation_events_type_check");
        }
        assertThat(rows()).containsExactly(answer + " 1 message.started -");
    }

    @Test
    void messageStartedCarriesNoText() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String bare = "msg_bare";
        database.inTenantTx(caller.tenantId(), tx -> tx.update("""
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status) VALUES (?, ?, ?, 'assistant', 'in_progress')
                """, bare, caller.tenantId(), conversation));

        assertRefused(() -> addRow(caller, bare, "message.started", "hello"), "conversation_events_text");
        assertThat(rows()).isEmpty();
    }

    /** A foreign key ignores row-level security, so only the tenant in the key keeps an event from another tenant's answer. */
    @Test
    void anEventCannotHangUnderAnotherTenantsAnswer() throws SQLException {
        Owner caller = newApplication();
        newAnswer(caller, newConversation(caller));
        Owner stranger = newApplication();
        String strangersAnswer = newAnswer(stranger, newConversation(stranger));

        assertRefused(() -> addRow(caller, strangersAnswer, "message.started", null), "violates foreign key constraint");
        assertThat(rows()).hasSize(2);
    }

    /** M2 deletes nothing, and an event never changes once written. */
    @Test
    void theEngineCanNeitherChangeNorDeleteAnEvent() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addDelta(caller, conversation, answer, "a");

        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("UPDATE conversation_events SET text = 'b'")),
                "permission denied");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("DELETE FROM conversation_events")),
                "permission denied");
        assertThat(rows()).containsExactly(answer + " 1 message.started -", answer + " 2 message.delta a");
    }

    /** The grant lets a writer lock a message, and nothing more: the table refuses any change. */
    @Test
    void aMessageCanBeLockedButNotChanged() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.addUserMessage(caller, conversation, "Hello?");

        List<String> locked = database.inTenantTx(caller.tenantId(), tx -> tx.query(
                "SELECT message_id FROM conversation_messages WHERE message_id = ? FOR UPDATE", row -> row.string("message_id"), answer));
        assertThat(locked).containsExactly(answer);

        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_messages SET status = 'completed', completed_at = now() WHERE message_id = ?", answer)),
                "A message changes only through its events");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("UPDATE conversation_messages SET text = text")),
                "A message changes only through its events");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("DELETE FROM conversation_messages")),
                "permission denied");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT role || ' ' || status FROM engine.conversation_messages ORDER BY ordinal"))
                .containsExactly("assistant in_progress", "user completed");
    }
}
