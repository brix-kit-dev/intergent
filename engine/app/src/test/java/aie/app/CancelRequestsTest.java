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
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.CancelRequest;
import aie.modules.conversation.api.Messages.CancelRequest.Recorded;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.api.Messages.Written;
import aie.modules.conversation.api.Messages.Written.Added;
import aie.modules.conversation.api.Messages.Written.CancelRequested;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M2-9-1: requests to cancel an answer, on the real scripts, as engine_app. A request is kept with an answer in progress,
 * once. From then on the answer takes no more deltas and no ending but message.cancelled, whoever writes, and the writer
 * learns why. An answer that ended first takes no request. Of a request and an ending at once, the first wins. The tests
 * that wait on other threads stop after a minute or two at most.
 */
@Testcontainers
class CancelRequestsTest {

    private static final String ADD_EVENT = """
            INSERT INTO conversation_events (tenant_id, message_id, type, text, input_tokens, output_tokens, code)
            VALUES (?, ?, ?, ?::text, ?::bigint, ?::bigint, ?::text) RETURNING seq
            """;
    private static final String ADD_REQUEST = """
            INSERT INTO conversation_cancel_requests (tenant_id, message_id) VALUES (?, ?) RETURNING message_id
            """;
    private static final String REFUSED_EVENT = "is to be cancelled, so it takes message.cancelled alone";
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

    /** An answer in progress with one delta, "Hal", as number 2. */
    private static String newAnswer(Owner caller, String conversation) {
        String answer = messages.startAssistantMessage(caller, conversation).orElseThrow().messageId();
        assertThat(messages.addDelta(caller, conversation, answer, "Hal")).isEqualTo(new Added(2));
        return answer;
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

    /** Adds a request as engine_app with SQL of its own, going around DatabaseMessages. */
    private static void addRequest(Owner caller, String message) {
        database.inTenantTx(caller.tenantId(), tx -> tx.query(ADD_REQUEST, row -> row.string("message_id"), caller.tenantId(), message));
    }

    private static void assertRefused(Runnable write, String reason) {
        assertThatThrownBy(write::run).isInstanceOf(DatabaseException.class).hasStackTraceContaining(reason);
    }

    /** What is really in the table, whatever row-level security lets the engine see. */
    private static List<String> requests() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_cancel_requests ORDER BY message_id");
    }

    /** The ending events really in the log. */
    private static List<String> endings() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT message_id || ' ' || seq || ' ' || type FROM engine.conversation_events
                WHERE type IN ('message.completed', 'message.cancelled', 'error') ORDER BY message_id
                """);
    }

    @Test
    void aRequestIsKeptWithAnAnswerInProgress() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        Message before = found(caller, conversation, answer);

        assertThat(messages.requestCancel(caller, conversation, answer)).isEqualTo(new Recorded());

        assertThat(requests()).containsExactly(answer);
        assertThat(TestDatabase.firstColumn(DATABASE, """
                SELECT (r.requested_at >= m.created_at AND r.requested_at <= now())::text
                FROM engine.conversation_cancel_requests r JOIN engine.conversation_messages m ON m.message_id = r.message_id
                """)).containsExactly("true");
        assertThat(found(caller, conversation, answer)).isEqualTo(before);
        assertThat(before.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(events(caller, conversation, answer)).extracting(Event::seq).containsExactly(1L, 2L);
    }

    @Test
    void askingAgainChangesNothing() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.requestCancel(caller, conversation, answer);
        List<String> first = TestDatabase.firstColumn(DATABASE, "SELECT requested_at::text FROM engine.conversation_cancel_requests");

        assertThat(messages.requestCancel(caller, conversation, answer)).isEqualTo(new Recorded());
        assertThat(messages.requestCancel(caller, conversation, answer)).isEqualTo(new Recorded());

        assertThat(requests()).containsExactly(answer);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT requested_at::text FROM engine.conversation_cancel_requests"))
                .isEqualTo(first);
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
    }

    @Test
    void anAnswerToBeCancelledTakesNoMoreDeltas() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.requestCancel(caller, conversation, answer);

        assertThat(messages.addDelta(caller, conversation, answer, "f")).isEqualTo(new CancelRequested());

        assertThat(events(caller, conversation, answer)).extracting(Event::seq).containsExactly(1L, 2L);
        assertThat(found(caller, conversation, answer).text()).isEqualTo("Hal");
    }

    /** Whatever its model does next, an answer to be cancelled can end only as cancelled, with what it generated. */
    @Test
    void anAnswerToBeCancelledEndsOnlyAsCancelled() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.requestCancel(caller, conversation, answer);

        CancelRequested refused = new CancelRequested();
        assertThat(messages.complete(caller, conversation, answer, USAGE)).isEqualTo(refused);
        assertThat(messages.fail(caller, conversation, answer, ErrorCode.MODEL_FAILED)).isEqualTo(refused);
        assertThat(messages.fail(caller, conversation, answer, ErrorCode.STREAM_INTERRUPTED)).isEqualTo(refused);
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);

        assertThat(messages.cancel(caller, conversation, answer, new Usage(5, 3))).isEqualTo(new Added(3));

        Message cancelled = found(caller, conversation, answer);
        assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);
        assertThat(cancelled.text()).isEqualTo("Hal");
        assertThat(cancelled.usage()).isEqualTo(new Usage(5, 3));
        assertThat(cancelled.code()).isNull();
        assertThat(cancelled.completedAt()).isNotNull();
        Event last = events(caller, conversation, answer).getLast();
        assertThat(last.type()).isEqualTo(EventType.MESSAGE_CANCELLED);
        assertThat(last.text()).isEqualTo("Hal");
        assertThat(last.usage()).isEqualTo(new Usage(5, 3));
        assertThat(endings()).containsExactly(answer + " 3 message.cancelled");
    }

    /** Once the answer is cancelled, it has ended like any other: a writer and a second request learn so. */
    @Test
    void anAnswerCancelledAfterARequestTakesNothingMore() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.requestCancel(caller, conversation, answer);
        messages.cancel(caller, conversation, answer, USAGE);
        Message cancelled = found(caller, conversation, answer);

        Written.AlreadyEnded over = new Written.AlreadyEnded(Status.CANCELLED);
        assertThat(messages.addDelta(caller, conversation, answer, "f")).isEqualTo(over);
        assertThat(messages.complete(caller, conversation, answer, USAGE)).isEqualTo(over);
        assertThat(messages.cancel(caller, conversation, answer, USAGE)).isEqualTo(over);
        assertThat(messages.fail(caller, conversation, answer, ErrorCode.STREAM_INTERRUPTED)).isEqualTo(over);
        assertThat(messages.requestCancel(caller, conversation, answer)).isEqualTo(new CancelRequest.AlreadyEnded(Status.CANCELLED));

        assertThat(found(caller, conversation, answer)).isEqualTo(cancelled);
        assertThat(endings()).containsExactly(answer + " 3 message.cancelled");
        assertThat(requests()).containsExactly(answer);
    }

    /**
     * An answer that ended before the request stays as it ended, and the request is not kept. A cancelled one had its own
     * request before it ended, since message.cancelled needs one (point 41); a later request adds nothing to it. A blocked
     * one lost its delta to the block (M3-4-3).
     */
    @ParameterizedTest
    @EnumSource(value = Status.class, names = "IN_PROGRESS", mode = EnumSource.Mode.EXCLUDE)
    void aRequestAfterTheEndingChangesNothing(Status ending) throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        switch (ending) {
            case COMPLETED -> messages.complete(caller, conversation, answer, USAGE);
            case CANCELLED -> {
                messages.requestCancel(caller, conversation, answer);
                messages.cancel(caller, conversation, answer, USAGE);
            }
            case BLOCKED -> messages.block(caller, conversation, answer, USAGE);
            default -> messages.fail(caller, conversation, answer, ErrorCode.MODEL_FAILED);
        }
        Message ended = found(caller, conversation, answer);
        List<String> before = requests();

        assertThat(messages.requestCancel(caller, conversation, answer)).isEqualTo(new CancelRequest.AlreadyEnded(ending));
        assertThat(messages.requestCancel(caller, conversation, answer)).isEqualTo(new CancelRequest.AlreadyEnded(ending));

        assertThat(before).isEqualTo(ending == Status.CANCELLED ? List.of(answer) : List.of());
        assertThat(requests()).isEqualTo(before);
        assertThat(found(caller, conversation, answer)).isEqualTo(ended);
        assertThat(ended.status()).isEqualTo(ending);
        assertThat(events(caller, conversation, answer)).hasSize(ending == Status.BLOCKED ? 2 : 3);
    }

    /**
     * A question, an unknown message, an answer of another conversation, application or tenant: none is an answer of the
     * caller's in this conversation, so none can be cancelled, and nothing is kept.
     */
    @Test
    void onlyAnAnswerInReachCanBeCancelled() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        CancelRequest.NotFound none = new CancelRequest.NotFound();
        assertThat(messages.requestCancel(caller, conversation, question)).isEqualTo(none);
        assertThat(messages.requestCancel(caller, conversation, "msg_00000000000000000000000000")).isEqualTo(none);
        assertThat(messages.requestCancel(caller, newConversation(caller), answer)).isEqualTo(none);
        assertThat(messages.requestCancel(otherApplication, conversation, answer)).isEqualTo(none);
        assertThat(messages.requestCancel(otherTenant, conversation, answer)).isEqualTo(none);

        assertThat(requests()).isEmpty();
        assertThat(messages.addDelta(caller, conversation, answer, "f")).isEqualTo(new Added(3));
    }

    /** A request holds its own answer only: the next answer of the conversation goes on and ends as it will. */
    @Test
    void aRequestHoldsItsOwnAnswerOnly() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String cancelling = newAnswer(caller, conversation);
        String other = newAnswer(caller, conversation);
        Owner otherTenant = newApplication();
        String otherConversation = newConversation(otherTenant);
        String elsewhere = newAnswer(otherTenant, otherConversation);
        messages.requestCancel(caller, conversation, cancelling);

        assertThat(messages.addDelta(caller, conversation, other, "f")).isEqualTo(new Added(3));
        assertThat(messages.complete(caller, conversation, other, USAGE)).isEqualTo(new Added(4));
        assertThat(messages.complete(otherTenant, otherConversation, elsewhere, USAGE)).isEqualTo(new Added(3));
        assertThat(found(caller, conversation, other).status()).isEqualTo(Status.COMPLETED);
        assertThat(messages.addDelta(caller, conversation, cancelling, "f")).isEqualTo(new CancelRequested());
    }

    /** SQL that goes around DatabaseMessages meets the same rule, from the database itself. */
    @Test
    void theDatabaseRefusesAnyEventButMessageCancelledAfterARequest() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        addRequest(caller, answer);

        assertRefused(() -> addRow(caller, answer, "message.delta", "f", null, null, null), REFUSED_EVENT);
        assertRefused(() -> addRow(caller, answer, "message.completed", null, 1L, 1L, null), REFUSED_EVENT);
        assertRefused(() -> addRow(caller, answer, "error", null, null, null, "model_failed"), REFUSED_EVENT);
        assertRefused(() -> addRow(caller, answer, "error", null, null, null, "stream_interrupted"), REFUSED_EVENT);
        assertRefused(() -> addRow(caller, answer, "message.started", null, null, null, null), REFUSED_EVENT);
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);

        assertThat(addRow(caller, answer, "message.cancelled", null, 1L, 1L, null)).isEqualTo(3);
        assertThat(found(caller, conversation, answer).text()).isEqualTo("Hal");
    }

    /** A request that goes around DatabaseMessages is held to an answer in progress, once, by the database itself. */
    @Test
    void theDatabaseTakesARequestOnlyForAnAnswerInProgress() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String completed = newAnswer(caller, conversation);
        messages.complete(caller, conversation, completed, USAGE);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();
        String answer = newAnswer(caller, conversation);

        assertRefused(() -> addRequest(caller, completed), "has ended, so it can no longer be cancelled");
        assertRefused(() -> addRequest(caller, question), "Only an answer can be cancelled");
        assertRefused(() -> addRequest(caller, "msg_00000000000000000000000000"), "conversation_cancel_requests_tenant_id_message_id_fkey");
        addRequest(caller, answer);
        assertRefused(() -> addRequest(caller, answer), "conversation_cancel_requests_pkey");

        assertThat(requests()).containsExactly(answer);
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_cancel_requests SET requested_at = now()")), "permission denied");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "DELETE FROM conversation_cancel_requests")), "permission denied");
        assertThat(requests()).containsExactly(answer);
    }

    /**
     * A request and an ending at once, again and again. Each time the first one wins: either the answer completes and
     * the request finds it ended, or the request is kept and the completion learns of it.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void ofARequestAndAnEndingAtOnceTheFirstWins() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String answer = newAnswer(caller, conversation);
                CountDownLatch ready = new CountDownLatch(2);
                CompletableFuture<CancelRequest> requesting = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(ready);
                    return messages.requestCancel(caller, conversation, answer);
                }, threads);
                CompletableFuture<Written> completing = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(ready);
                    return messages.complete(caller, conversation, answer, USAGE);
                }, threads);
                CancelRequest requested = requesting.get(30, TimeUnit.SECONDS);
                Written completed = completing.get(30, TimeUnit.SECONDS);

                Status status = found(caller, conversation, answer).status();
                if (completed instanceof Added) {
                    assertThat(status).isEqualTo(Status.COMPLETED);
                    assertThat(requested).isEqualTo(new CancelRequest.AlreadyEnded(Status.COMPLETED));
                    assertThat(requests()).doesNotContain(answer);
                } else {
                    assertThat(completed).isEqualTo(new CancelRequested());
                    assertThat(requested).isEqualTo(new Recorded());
                    assertThat(status).isEqualTo(Status.IN_PROGRESS);
                    assertThat(requests()).contains(answer);
                    assertThat(messages.cancel(caller, conversation, answer, USAGE)).isEqualTo(new Added(3));
                }
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(endings()).hasSize(20);
        assertThat(TestDatabase.firstColumn(DATABASE, """
                SELECT r.message_id FROM engine.conversation_cancel_requests r
                JOIN engine.conversation_messages m ON m.message_id = r.message_id
                WHERE m.status <> 'cancelled'
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
     * The request holds the answer's lock. A writer has already looked at the answer and found no request, so it waits for
     * the lock; once the request commits, the database refuses the writer's ending, and the writer learns of the request
     * instead of meeting a database error.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aWriterThatWaitedForARequestLearnsOfIt() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            tx.query(ADD_REQUEST, row -> row.string("message_id"), caller.tenantId(), answer);
            holding.countDown();
            await(release);
            return null;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Written> writer = CompletableFuture.supplyAsync(
                () -> messages.complete(caller, conversation, answer, USAGE), threads);
        waitForAWriterOnALock();
        assertThat(writer).isNotDone();

        release.countDown();

        request.get(30, TimeUnit.SECONDS);
        assertThat(writer.get(30, TimeUnit.SECONDS)).isEqualTo(new CancelRequested());
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(endings()).isEmpty();
        threads.shutdown();
    }

    /**
     * The ending holds the answer's lock. A request has already looked at the answer and found it in progress, so it waits
     * for the lock; once the ending commits, the database refuses the request, which learns how the answer ended.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRequestThatWaitedForAnEndingLearnsHowTheAnswerEnded() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Long> ending = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            long number = tx.query(ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, "message.completed",
                    null, 7L, 8L, null).getFirst();
            holding.countDown();
            await(release);
            return number;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<CancelRequest> request = CompletableFuture.supplyAsync(
                () -> messages.requestCancel(caller, conversation, answer), threads);
        waitForAWriterOnALock();
        assertThat(request).isNotDone();

        release.countDown();

        assertThat(ending.get(30, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo(new CancelRequest.AlreadyEnded(Status.COMPLETED));
        assertThat(requests()).isEmpty();
        assertThat(found(caller, conversation, answer).usage()).isEqualTo(new Usage(7, 8));
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
}
