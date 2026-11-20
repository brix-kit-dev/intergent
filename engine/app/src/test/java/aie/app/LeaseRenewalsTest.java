package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.gateway.api.Gateway;
import aie.core.gateway.internal.Route;
import aie.core.gateway.internal.RoutingGateway;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Answers.Start;
import aie.modules.conversation.api.Answers.Start.Started;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.api.Messages.Written;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.modules.conversation.internal.GeneratingAnswers;
import aie.ports.ModelPort;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M2-11-2: the instance renews the lease of each answer it writes, on the real scripts, as engine_app through a pool, with
 * the real gateway and stub models. Renewals come every 200 ms here instead of 5 seconds. A renewal tells the writer of a
 * request to cancel, so a model that writes nothing stops too; it tells the writer that a reader ended the answer, so the
 * writer stops and writes nothing more. Each test stops after a minute.
 */
@Testcontainers
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LeaseRenewalsTest {

    private static final Duration RENEWAL = Duration.ofMillis(200);
    private static final long WAIT_SECONDS = 20;

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgPool pool;
    private static Messages messages;
    private static Conversations conversations;
    private static EndUsers endUsers;
    private static Provisioning provisioning;

    private Owner caller;
    private String conversation;

    @BeforeAll
    static void createRolesMigrateAndOpenAPool() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 6);
        messages = new DatabaseMessages(pool);
        conversations = new DatabaseConversations(pool);
        endUsers = new DatabaseEndUsers(pool);
        provisioning = new DatabaseProvisioning(pool);
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    @BeforeEach
    void startAConversationInEmptyTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.conversation_conversations CASCADE");
        String tenant = provisioning.createTenant("jp1", null);
        caller = new Owner("key_unused", tenant, provisioning.createApplication(tenant, "test", "internal"));
        conversation = conversations.start(caller, endUsers.resolve(caller, "user-1"), null).conversationId();
    }

    private static Gateway stub(ModelPort model) {
        return new RoutingGateway(Map.of("stub", new RoutingGateway.Model(model, DataClass.SENSITIVE)),
                Map.of(Answers.TASK, new Route(List.of("stub"), Duration.ofSeconds(30), CheckMode.SENTENCE, TestApi.prompts(List.of("stub")))), Models.checks(), pool::insideTx);
    }

    /** Renewals every 200 ms, and room for a few answers. */
    private static GeneratingAnswers renewingOften(Messages through, ModelPort model) {
        return new GeneratingAnswers(through, stub(model), 4, Duration.ofSeconds(5), RENEWAL);
    }

    private static Started started(Start start) {
        assertThat(start).isInstanceOf(Started.class);
        return (Started) start;
    }

    private static String idOf(Started started) {
        return started.asked().answer().messageId();
    }

    private Message answerOf(Started started) {
        return messages.find(caller, conversation, idOf(started)).orElseThrow();
    }

    private static void sleep(Duration time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A model that writes "half " and then nothing more, until it is asked to stop. */
    private static ModelPort silent(CountDownLatch writing) {
        return (request, output) -> {
            output.text("half ");
            writing.countDown();
            while (!request.cancellation().requested()) {
                sleep(Duration.ofMillis(5));
            }
            return new ModelPort.Cancelled(new ModelPort.Usage(2, 5));
        };
    }

    /** When the lease runs out, in milliseconds since the epoch, as the database holds it. */
    private static long leaseEnd(String answer) throws SQLException {
        return Long.parseLong(TestDatabase.firstColumn(DATABASE, "SELECT (extract(epoch FROM expires_at) * 1000)::bigint::text"
                + " FROM engine.conversation_answer_leases WHERE message_id = '" + answer + "'").getFirst());
    }

    /** While the model writes nothing, the lease keeps moving on, far ahead of the moment. */
    @Test
    void theLeaseIsRenewedWhileTheAnswerIsWritten() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        try (GeneratingAnswers answers = renewingOften(messages, silent(writing))) {
            Started started = started(answers.ask(caller, conversation, "Long?", DataClass.PUBLIC));
            assertThat(writing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            long first = leaseEnd(idOf(started));

            Thread.sleep(1000);

            assertThat(leaseEnd(idOf(started))).isGreaterThan(first + 500);
            assertThat(answerOf(started).status()).isEqualTo(Status.IN_PROGRESS);
        }
    }

    /**
     * The cancel comes to another instance, and the model writes nothing more, so no refused piece tells the writer. The
     * next renewal does: the model stops, and the answer ends as cancelled with the usage the model reports.
     */
    @Test
    void aSilentModelLearnsOfACancelAtTheNextRenewal() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        try (GeneratingAnswers writer = renewingOften(messages, silent(writing))) {
            Started started = started(writer.ask(caller, conversation, "Quiet?", DataClass.PUBLIC));
            assertThat(writing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            long asked = System.nanoTime();
            assertThat(messages.requestCancel(caller, conversation, idOf(started))).isEqualTo(new Messages.CancelRequest.Recorded());
            started.ended().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
            Duration took = Duration.ofNanos(System.nanoTime() - asked);

            assertThat(took).isLessThan(Duration.ofSeconds(2));
            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.text()).isEqualTo("half ");
            assertThat(answer.usage()).isEqualTo(new Usage(2, 5));
        }
    }

    /**
     * A reader ended the answer, as it does once a lease ran out. The model writes nothing more, so the next renewal is
     * what tells the writer: the model stops, the writer writes nothing more, and its slot is free again.
     */
    @Test
    void aWriterLearnsAtItsRenewalThatTheAnswerEnded() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        try (GeneratingAnswers writer = new GeneratingAnswers(messages, stub(silent(writing)), 1, Duration.ofSeconds(5), RENEWAL)) {
            Started started = started(writer.ask(caller, conversation, "Gone?", DataClass.PUBLIC));
            assertThat(writing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(messages.fail(caller, conversation, idOf(started), ErrorCode.STREAM_INTERRUPTED)).isInstanceOf(Written.Added.class);
            started.ended().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);

            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.FAILED);
            assertThat(answer.code()).isEqualTo(ErrorCode.STREAM_INTERRUPTED);
            assertThat(messages.eventsAfter(caller, conversation, idOf(started), 0, 100)).extracting(Event::seq).containsExactly(1L, 2L, 3L);
            assertThat(writer.ask(caller, conversation, "Next?", DataClass.PUBLIC)).isInstanceOf(Started.class);
        }
    }

    /** Messages whose first renewals fail, as when the database is gone for a moment. Everything else goes through. */
    private static Messages renewalsFailFirst(int failing, AtomicInteger renewals) {
        return (Messages) Proxy.newProxyInstance(Messages.class.getClassLoader(), new Class<?>[] {Messages.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("renew") && renewals.incrementAndGet() <= failing) {
                        throw new IllegalStateException("The test's database went away for a moment");
                    }
                    try {
                        return method.invoke(messages, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** A renewal that fails costs only its round: the next ones go on, and the lease keeps moving. */
    @Test
    void aFailedRenewalIsTriedAgain() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        AtomicInteger renewals = new AtomicInteger();
        try (GeneratingAnswers answers = renewingOften(renewalsFailFirst(2, renewals), silent(writing))) {
            Started started = started(answers.ask(caller, conversation, "Flaky?", DataClass.PUBLIC));
            assertThat(writing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            long first = leaseEnd(idOf(started));

            Thread.sleep(1500);

            assertThat(renewals.get()).isGreaterThan(4);
            assertThat(leaseEnd(idOf(started))).isGreaterThan(first + 500);
        }
    }

    /**
     * Once the instance has stopped, no lease is renewed any more, not even that of an answer whose model ignored the stop
     * and is still under way: a reader may end that one once its lease runs out.
     */
    @Test
    void aStoppedInstanceRenewsNoMore() throws Exception {
        AtomicInteger renewals = new AtomicInteger();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelPort deaf = (request, output) -> {
            output.text("deaf ");
            writing.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ModelPort.Completed(new ModelPort.Usage(1, 1));
        };
        GeneratingAnswers answers = new GeneratingAnswers(renewalsFailFirst(0, renewals), stub(deaf), 4, Duration.ZERO, RENEWAL);
        try {
            started(answers.ask(caller, conversation, "Stop?", DataClass.PUBLIC));
            assertThat(writing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);
            assertThat(renewals.get()).isPositive();

            answers.close();
            int afterClose = renewals.get();
            Thread.sleep(800);

            assertThat(renewals.get()).isEqualTo(afterClose);
        } finally {
            release.countDown();
        }
    }

    @Test
    void theRenewalIsFiveSecondsAndFarInsideTheLease() {
        assertThat(GeneratingAnswers.RENEWAL).isEqualTo(Duration.ofSeconds(5));
        Gateway any = stub(silent(new CountDownLatch(1)));
        for (Duration wrong : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(16)}) {
            assertThatThrownBy(() -> new GeneratingAnswers(messages, any, 1, Duration.ZERO, wrong))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        new GeneratingAnswers(messages, any, 1, Duration.ZERO, Duration.ofSeconds(15)).close();
    }
}
