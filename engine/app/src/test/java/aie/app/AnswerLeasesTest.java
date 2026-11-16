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
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Expiry;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Renewal;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.api.Messages.Written;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M2-11-1: the lease of an answer's writer, on the real scripts, as engine_app. An answer starts with a lease of 30
 * seconds, and its writer renews it while the answer goes on. Once the lease has run out, a reader ends the answer for the
 * writer: error with stream_interrupted, or message.cancelled with no tokens when someone asked to cancel it. A renewal and
 * a reader take the lease in turn, so a writer that renews in time keeps its answer. The tests that wait on other threads
 * stop after a minute or two at most.
 */
@Testcontainers
class AnswerLeasesTest {

    private static final String HOLD = """
            SELECT expires_at FROM conversation_answer_leases WHERE tenant_id = ? AND message_id = ? FOR UPDATE
            """;
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
    private static final List<String> RUNG = new CopyOnWriteArrayList<>();

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
        messages = new DatabaseMessages(database, new EventSignals() {

            @Override
            public CompletableFuture<Void> next(String messageId) {
                return new CompletableFuture<>();
            }

            @Override
            public void ring(String messageId) {
                RUNG.add(messageId);
            }

            @Override
            public void forget(String messageId, CompletableFuture<Void> signal) {
            }
        });
        conversations = new DatabaseConversations(database);
        endUsers = new DatabaseEndUsers(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.conversation_conversations CASCADE");
        RUNG.clear();
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
        assertThat(messages.addDelta(caller, conversation, answer, "Hal")).isEqualTo(new Written.Added(2));
        return answer;
    }

    private static Message found(Owner caller, String conversation, String answer) {
        return messages.find(caller, conversation, answer).orElseThrow();
    }

    /** Seconds from now to the lease's end, as the database sees them; negative once it has run out. */
    private static double secondsLeft(String answer) throws SQLException {
        return Double.parseDouble(TestDatabase.firstColumn(DATABASE, "SELECT extract(epoch FROM expires_at - now())::text"
                + " FROM engine.conversation_answer_leases WHERE message_id = '" + answer + "'").getFirst());
    }

    private static void leaseEnds(String answer, String when) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "UPDATE engine.conversation_answer_leases SET expires_at = " + when
                + " WHERE message_id = '" + answer + "'");
    }

    private static void runOut(String answer) throws SQLException {
        leaseEnds(answer, "now() - interval '1 second'");
    }

    private static void assertRefused(Runnable write, String reason) {
        assertThatThrownBy(write::run).isInstanceOf(DatabaseException.class).hasStackTraceContaining(reason);
    }

    private static List<String> leases() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_answer_leases ORDER BY message_id");
    }

    /** An answer starts with a lease of 30 seconds, whichever way it starts; a question has none. */
    @Test
    void anAnswerStartsWithALease() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String started = messages.startAssistantMessage(caller, conversation).orElseThrow().messageId();
        Messages.Asked asked = messages.ask(caller, conversation, "Hello?").orElseThrow();

        assertThat(leases()).containsExactlyInAnyOrder(started, asked.answer().messageId());
        assertThat(secondsLeft(started)).isBetween(25.0, 30.0);
        assertThat(secondsLeft(asked.answer().messageId())).isBetween(25.0, 30.0);
        assertThat(Messages.LEASE).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aRenewalPushesTheLeaseOn() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        leaseEnds(answer, "now() + interval '1 second'");

        assertThat(messages.renew(caller, conversation, answer)).isEqualTo(new Renewal.Renewed(false));

        assertThat(secondsLeft(answer)).isBetween(25.0, 30.0);
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
    }

    /** A writer whose model writes nothing for a while still learns of a request to cancel, at its next renewal. */
    @Test
    void aRenewalTellsOfARequestToCancel() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.requestCancel(caller, conversation, answer);

        assertThat(messages.renew(caller, conversation, answer)).isEqualTo(new Renewal.Renewed(true));
    }

    @Test
    void anEndedAnswerHasNoLeaseToRenew() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        runOut(answer);
        messages.complete(caller, conversation, answer, USAGE);

        assertThat(messages.renew(caller, conversation, answer)).isEqualTo(new Renewal.AlreadyEnded(Status.COMPLETED));

        assertThat(secondsLeft(answer)).isNegative();
    }

    /** A question, an unknown message, an answer of another conversation, application or tenant: no lease to renew. */
    @Test
    void aRenewalReachesOnlyAnAnswerInReach() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());
        leaseEnds(answer, "now() + interval '2 seconds'");

        Renewal.NotFound none = new Renewal.NotFound();
        assertThat(messages.renew(caller, conversation, question)).isEqualTo(none);
        assertThat(messages.renew(caller, conversation, "msg_00000000000000000000000000")).isEqualTo(none);
        assertThat(messages.renew(caller, newConversation(caller), answer)).isEqualTo(none);
        assertThat(messages.renew(otherApplication, conversation, answer)).isEqualTo(none);
        assertThat(messages.renew(otherTenant, conversation, answer)).isEqualTo(none);

        assertThat(secondsLeft(answer)).isLessThan(3.0);
    }

    @Test
    void aRunningLeaseKeepsTheAnswer() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);

        assertThat(messages.endIfExpired(caller, conversation, answer)).isEqualTo(new Expiry.Running());

        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(messages.addDelta(caller, conversation, answer, "f")).isEqualTo(new Written.Added(3));
        assertThat(RUNG).containsExactly(answer, answer);
    }

    /** The writer has gone: a reader ends the answer as interrupted, and the writer, should it come back, writes nothing. */
    @Test
    void anExpiredLeaseEndsTheAnswerAsInterrupted() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        runOut(answer);
        RUNG.clear();

        assertThat(messages.endIfExpired(caller, conversation, answer)).isEqualTo(new Expiry.Ended(EventType.ERROR));

        Message ended = found(caller, conversation, answer);
        assertThat(ended.status()).isEqualTo(Status.FAILED);
        assertThat(ended.code()).isEqualTo(ErrorCode.STREAM_INTERRUPTED);
        assertThat(ended.text()).isEqualTo("Hal");
        assertThat(RUNG).containsExactly(answer);
        assertThat(messages.addDelta(caller, conversation, answer, "f")).isEqualTo(new Written.AlreadyEnded(Status.FAILED));
        assertThat(messages.renew(caller, conversation, answer)).isEqualTo(new Renewal.AlreadyEnded(Status.FAILED));
        assertThat(messages.endIfExpired(caller, conversation, answer)).isEqualTo(new Expiry.AlreadyEnded(Status.FAILED));
    }

    /** Someone asked to cancel the answer: its only ending is message.cancelled, and no one knows what the model used. */
    @Test
    void anExpiredLeaseOfAnAnswerToBeCancelledEndsItCancelled() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        messages.requestCancel(caller, conversation, answer);
        runOut(answer);

        assertThat(messages.endIfExpired(caller, conversation, answer)).isEqualTo(new Expiry.Ended(EventType.MESSAGE_CANCELLED));

        Message ended = found(caller, conversation, answer);
        assertThat(ended.status()).isEqualTo(Status.CANCELLED);
        assertThat(ended.text()).isEqualTo("Hal");
        assertThat(ended.usage()).isEqualTo(new Usage(0, 0));
    }

    @Test
    void aReaderReachesOnlyAnAnswerInReach() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner otherTenant = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());
        runOut(answer);

        Expiry.NotFound none = new Expiry.NotFound();
        assertThat(messages.endIfExpired(caller, conversation, question)).isEqualTo(none);
        assertThat(messages.endIfExpired(caller, conversation, "msg_00000000000000000000000000")).isEqualTo(none);
        assertThat(messages.endIfExpired(caller, newConversation(caller), answer)).isEqualTo(none);
        assertThat(messages.endIfExpired(otherApplication, conversation, answer)).isEqualTo(none);
        assertThat(messages.endIfExpired(otherTenant, conversation, answer)).isEqualTo(none);

        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
    }

    /** SQL that goes around DatabaseMessages meets the same rules, from the database itself. */
    @Test
    void theDatabaseKeepsALeaseWithItsAnswerInProgress() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        String other = newAnswer(caller, conversation);
        String done = newAnswer(caller, conversation);
        messages.complete(caller, conversation, done, USAGE);
        String question = messages.addUserMessage(caller, conversation, "Hello?").orElseThrow().messageId();
        TestDatabase.asAdmin(DATABASE, "DELETE FROM engine.conversation_answer_leases WHERE message_id IN ('" + done + "')");

        assertRefused(() -> lease(caller, question), "Only an answer has a lease");
        assertRefused(() -> lease(caller, done), "has ended, so its lease is over");
        assertRefused(() -> lease(caller, answer), "conversation_answer_leases_pkey");
        assertRefused(() -> lease(caller, "msg_00000000000000000000000000"), "conversation_answer_leases_tenant_id_message_id_fkey");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_answer_leases SET message_id = ? WHERE message_id = ?", other, answer)), "A lease stays with its answer");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "DELETE FROM conversation_answer_leases")), "permission denied");
        messages.complete(caller, conversation, other, USAGE);
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_answer_leases SET expires_at = now() WHERE message_id = ?", other)), "has ended, so its lease is over");

        assertThat(leases()).containsExactlyInAnyOrder(answer, other);
    }

    private static void lease(Owner caller, String message) {
        database.inTenantTx(caller.tenantId(), tx -> tx.update("INSERT INTO conversation_answer_leases (tenant_id, message_id,"
                + " expires_at) VALUES (?, ?, now() + interval '30 seconds')", caller.tenantId(), message));
    }

    /**
     * A renewal and a reader at once, again and again, on a lease that has run out. Either the renewal comes first and the
     * reader finds the lease running, or the reader comes first and the renewal finds the answer ended; never both.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void ofARenewalAndAReaderAtOnceTheFirstWins() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        int renewedFirst = 0;
        int endedFirst = 0;
        try {
            for (int round = 0; round < 20; round++) {
                String answer = newAnswer(caller, conversation);
                runOut(answer);
                CountDownLatch ready = new CountDownLatch(2);
                CompletableFuture<Renewal> renewing = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(ready);
                    return messages.renew(caller, conversation, answer);
                }, threads);
                CompletableFuture<Expiry> reading = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(ready);
                    return messages.endIfExpired(caller, conversation, answer);
                }, threads);
                Renewal renewal = renewing.get(30, TimeUnit.SECONDS);
                Expiry expiry = reading.get(30, TimeUnit.SECONDS);

                Status status = found(caller, conversation, answer).status();
                if (expiry instanceof Expiry.Ended) {
                    assertThat(renewal).isEqualTo(new Renewal.AlreadyEnded(Status.FAILED));
                    assertThat(status).isEqualTo(Status.FAILED);
                    endedFirst++;
                } else {
                    assertThat(expiry).isEqualTo(new Expiry.Running());
                    assertThat(renewal).isEqualTo(new Renewal.Renewed(false));
                    assertThat(status).isEqualTo(Status.IN_PROGRESS);
                    renewedFirst++;
                }
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(renewedFirst + endedFirst).isEqualTo(20);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A reader holds the lease and ends the answer. A renewal waits for the lease; once the reader commits, the renewal
     * finds the answer ended, instead of meeting a database error.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRenewalThatWaitedForAReaderLearnsTheAnswerEnded() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        runOut(answer);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Long> reader = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            tx.query(HOLD, row -> row.time("expires_at"), caller.tenantId(), answer);
            long number = tx.query(ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, "error", null, null, null,
                    "stream_interrupted").getFirst();
            holding.countDown();
            await(release);
            return number;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Renewal> renewal = CompletableFuture.supplyAsync(
                () -> messages.renew(caller, conversation, answer), threads);
        waitForAWriterOnALock();
        assertThat(renewal).isNotDone();

        release.countDown();

        assertThat(reader.get(30, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(renewal.get(30, TimeUnit.SECONDS)).isEqualTo(new Renewal.AlreadyEnded(Status.FAILED));
        threads.shutdown();
    }

    /**
     * The writer ends its answer while a renewal comes. The renewal waits for the answer's lock; once the ending commits,
     * the renewal finds the answer ended, so a lease is never renewed for an answer that is ending.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRenewalThatWaitedForAnEndingLearnsOfIt() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Long> ending = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            long number = tx.query(ADD_EVENT, row -> row.number("seq"), caller.tenantId(), answer, "message.completed", null,
                    7L, 8L, null).getFirst();
            holding.countDown();
            await(release);
            return number;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Renewal> renewal = CompletableFuture.supplyAsync(
                () -> messages.renew(caller, conversation, answer), threads);
        waitForAWriterOnALock();
        assertThat(renewal).isNotDone();

        release.countDown();

        assertThat(ending.get(30, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(renewal.get(30, TimeUnit.SECONDS)).isEqualTo(new Renewal.AlreadyEnded(Status.COMPLETED));
        threads.shutdown();
    }

    /**
     * A renewal holds the lease. A reader that found it run out waits for the lease; once the renewal commits, the reader
     * finds the lease running again and leaves the answer to its writer.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aReaderThatWaitedForARenewalLeavesTheAnswer() throws Exception {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String answer = newAnswer(caller, conversation);
        runOut(answer);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        CompletableFuture<Integer> renewal = CompletableFuture.supplyAsync(() -> database.inTenantTx(caller.tenantId(), tx -> {
            int renewed = tx.update("UPDATE conversation_answer_leases SET expires_at = now() + interval '30 seconds'"
                    + " WHERE message_id = ?", answer);
            holding.countDown();
            await(release);
            return renewed;
        }), threads);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Expiry> reader = CompletableFuture.supplyAsync(
                () -> messages.endIfExpired(caller, conversation, answer), threads);
        waitForAWriterOnALock();
        assertThat(reader).isNotDone();

        release.countDown();

        assertThat(renewal.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(reader.get(30, TimeUnit.SECONDS)).isEqualTo(new Expiry.Running());
        assertThat(found(caller, conversation, answer).status()).isEqualTo(Status.IN_PROGRESS);
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
