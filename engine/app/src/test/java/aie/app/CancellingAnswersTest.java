package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.modelfake.FakeModel;
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
import aie.modules.conversation.api.Messages.CancelRequest;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.api.Messages.Usage;
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
 * M2-9-2: the writer of an answer answers a request to cancel it, on the real scripts, as engine_app through a pool, with
 * the real gateway. A cancel on the writer's own instance stops the model at once; one from another instance stops it at
 * its next piece, which the log refuses. Either way the answer ends with message.cancelled, its text what the log holds
 * and its usage what the model reports. Each test waits for an answer for 20 seconds at most, and stops after a minute.
 */
@Testcontainers
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CancellingAnswersTest {

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

    /** The real gateway with the fake model, on a route that lets an answer take a minute. */
    private static Gateway fake() {
        return new RoutingGateway(Map.of(FakeModel.MODEL, new FakeModel()),
                Map.of(Answers.TASK, new Route(List.of(FakeModel.MODEL), Duration.ofSeconds(60), CheckMode.SENTENCE, TestApi.prompts(List.of(FakeModel.MODEL)))), pool::insideTx);
    }

    /** The real gateway with a stub model. */
    private static Gateway stub(ModelPort model) {
        return new RoutingGateway(Map.of("stub", model),
                Map.of(Answers.TASK, new Route(List.of("stub"), Duration.ofSeconds(30), CheckMode.SENTENCE, TestApi.prompts(List.of("stub")))), pool::insideTx);
    }

    private static Started started(Start start) {
        assertThat(start).isInstanceOf(Started.class);
        return (Started) start;
    }

    private static void awaitEnd(Started started) throws Exception {
        started.ended().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static String idOf(Started started) {
        return started.asked().answer().messageId();
    }

    private Message answerOf(Started started) {
        return messages.find(caller, conversation, idOf(started)).orElseThrow();
    }

    private List<Event> eventsOf(Started started) {
        return messages.eventsAfter(caller, conversation, idOf(started), 0, 1000);
    }

    private void waitForEvents(Started started, int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (eventsOf(started).size() < count) {
            if (System.nanoTime() > until) {
                throw new AssertionError("The answer did not reach " + count + " events");
            }
            Thread.sleep(10);
        }
    }

    /** How the fake model counts tokens: one a character. */
    private static long characters(String text) {
        return text.codePointCount(0, text.length());
    }

    private static void sleep(Duration time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A model that writes "all " and ends as the test says once it lets it go, deaf to any cancel. */
    private static ModelPort writesOnceThen(CountDownLatch written, CountDownLatch release, ModelPort.Result result) {
        return (request, output) -> {
            output.text("all ");
            written.countDown();
            await(release);
            return result;
        };
    }

    /** The fake model waits two seconds for its next piece, but the cancel on its own instance stops it at once. */
    @Test
    void aCancelHereStopsTheModelAtOnce() throws Exception {
        String question = "[fake pieces=5 delay=2s] Slow";
        try (GeneratingAnswers answers = new GeneratingAnswers(messages, fake())) {
            Started started = started(answers.ask(caller, conversation, question, DataClass.PUBLIC));
            waitForEvents(started, 2);

            long asked = System.nanoTime();
            assertThat(answers.cancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            awaitEnd(started);
            Duration took = Duration.ofNanos(System.nanoTime() - asked);

            assertThat(took).isLessThan(Duration.ofSeconds(1));
            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.text()).isEqualTo("Piece 1 of 5. ");
            assertThat(answer.usage()).isEqualTo(new Usage(characters(question), characters("Piece 1 of 5. ")));
            assertThat(answer.code()).isNull();
            assertThat(eventsOf(started)).extracting(Event::type)
                    .containsExactly(EventType.MESSAGE_STARTED, EventType.MESSAGE_DELTA, EventType.MESSAGE_CANCELLED);
        }
    }

    /**
     * The cancel comes to another instance, which only keeps the request. The writer learns of it when the log refuses its
     * next piece, within the model's 200 ms between pieces. That piece is not in the answer, but the model made it, so
     * the usage counts it.
     */
    @Test
    void aCancelFromAnotherInstanceStopsTheWriterAtItsNextPiece() throws Exception {
        String question = "[fake pieces=50 delay=200ms] Long";
        try (GeneratingAnswers writer = new GeneratingAnswers(messages, fake());
                GeneratingAnswers other = new GeneratingAnswers(messages, fake())) {
            Started started = started(writer.ask(caller, conversation, question, DataClass.PUBLIC));
            waitForEvents(started, 3);

            long asked = System.nanoTime();
            assertThat(other.cancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            awaitEnd(started);
            Duration took = Duration.ofNanos(System.nanoTime() - asked);

            assertThat(took).isLessThan(Duration.ofSeconds(1));
            Message answer = answerOf(started);
            List<Event> events = eventsOf(started);
            long deltas = events.stream().filter(event -> event.type() == EventType.MESSAGE_DELTA).count();
            StringBuilder logged = new StringBuilder();
            for (int piece = 1; piece <= deltas; piece++) {
                logged.append("Piece ").append(piece).append(" of 50. ");
            }
            String refused = "Piece " + (deltas + 1) + " of 50. ";
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.text()).isEqualTo(logged.toString());
            assertThat(answer.usage()).isEqualTo(new Usage(characters(question), characters(logged.toString()) + characters(refused)));
            assertThat(events.getLast().type()).isEqualTo(EventType.MESSAGE_CANCELLED);
            assertThat(events.getLast().seq()).isEqualTo(deltas + 2);
        }
    }

    /** The model had written its last piece when the request came, and completes: the request came first, so it wins. */
    @Test
    void aRequestAfterTheLastPieceStillEndsTheAnswerCancelled() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelPort completes = writesOnceThen(written, release, new ModelPort.Completed(new ModelPort.Usage(3, 4)));
        try (GeneratingAnswers writer = new GeneratingAnswers(messages, stub(completes));
                GeneratingAnswers other = new GeneratingAnswers(messages, stub(completes))) {
            Started started = started(writer.ask(caller, conversation, "Done soon?", DataClass.PUBLIC));
            assertThat(written.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            waitForEvents(started, 2);

            assertThat(other.cancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            release.countDown();
            awaitEnd(started);

            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.text()).isEqualTo("all ");
            assertThat(answer.usage()).isEqualTo(new Usage(3, 4));
            assertThat(eventsOf(started)).extracting(Event::type)
                    .containsExactly(EventType.MESSAGE_STARTED, EventType.MESSAGE_DELTA, EventType.MESSAGE_CANCELLED);
        }
    }

    /** The model fails after the request: the answer ends as cancelled all the same, and is not blamed on the model. */
    @Test
    void aModelThatFailsAfterARequestStillEndsTheAnswerCancelled() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelPort fails = writesOnceThen(written, release, new ModelPort.Failed("broke", 1, new ModelPort.Usage(3, 4)));
        try (GeneratingAnswers writer = new GeneratingAnswers(messages, stub(fails));
                GeneratingAnswers other = new GeneratingAnswers(messages, stub(fails))) {
            Started started = started(writer.ask(caller, conversation, "Breaks?", DataClass.PUBLIC));
            assertThat(written.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            waitForEvents(started, 2);

            assertThat(other.cancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            release.countDown();
            awaitEnd(started);

            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.code()).isNull();
            assertThat(answer.text()).isEqualTo("all ");
            assertThat(answer.usage()).isEqualTo(new Usage(3, 4));
        }
    }

    /**
     * A model that writes nothing more never meets the refusal, so a request from another instance does not reach it
     * (until M2-11's lease). When the instance stops, the answer ends as cancelled, not as interrupted: the request came
     * first.
     */
    @Test
    void anInstanceThatStopsWithARequestEndsTheAnswerCancelled() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        ModelPort silent = (request, output) -> {
            output.text("half ");
            writing.countDown();
            while (!request.cancellation().requested()) {
                sleep(Duration.ofMillis(5));
            }
            return new ModelPort.Cancelled(new ModelPort.Usage(2, 5));
        };
        GeneratingAnswers writer = new GeneratingAnswers(messages, stub(silent), 4, Duration.ofMillis(100));
        try (GeneratingAnswers other = new GeneratingAnswers(messages, stub(silent))) {
            Started started = started(writer.ask(caller, conversation, "Quiet?", DataClass.PUBLIC));
            assertThat(writing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            waitForEvents(started, 2);

            assertThat(other.cancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            Thread.sleep(300);
            assertThat(answerOf(started).status()).isEqualTo(Status.IN_PROGRESS);

            writer.close();
            awaitEnd(started);

            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.code()).isNull();
            assertThat(answer.text()).isEqualTo("half ");
            assertThat(answer.usage()).isEqualTo(new Usage(2, 5));
        }
    }

    /** A cancel for an answer that ended changes nothing, and one out of the caller's reach stops nothing. */
    @Test
    void aCancelThatIsNotKeptStopsNothing() throws Exception {
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "internal"));
        try (GeneratingAnswers answers = new GeneratingAnswers(messages, fake())) {
            Started done = started(answers.ask(caller, conversation, "[fake pieces=2 delay=1ms] Quick", DataClass.PUBLIC));
            awaitEnd(done);
            Message ended = answerOf(done);
            assertThat(answers.cancel(caller, conversation, idOf(done))).isEqualTo(new CancelRequest.AlreadyEnded(Status.COMPLETED));
            assertThat(answerOf(done)).isEqualTo(ended);

            Started going = started(answers.ask(caller, conversation, "[fake pieces=3 delay=300ms] Mine", DataClass.PUBLIC));
            waitForEvents(going, 2);
            assertThat(answers.cancel(otherApplication, conversation, idOf(going))).isEqualTo(new CancelRequest.NotFound());
            assertThat(answers.cancel(caller, conversation, going.asked().question().messageId()))
                    .isEqualTo(new CancelRequest.NotFound());
            awaitEnd(going);

            assertThat(answerOf(going).status()).isEqualTo(Status.COMPLETED);
            assertThat(answerOf(going).text()).isEqualTo("Piece 1 of 3. Piece 2 of 3. Piece 3 of 3. ");
        }
    }

    /** Cancelled before its first piece, the answer ends empty, and its slot is free for the next ask. */
    @Test
    void aCancelledAnswerGivesItsSlotBack() throws Exception {
        String question = "[fake pieces=5 delay=2s] Slow";
        try (GeneratingAnswers answers = new GeneratingAnswers(messages, fake(), 1, Duration.ofSeconds(5))) {
            Started started = started(answers.ask(caller, conversation, question, DataClass.PUBLIC));
            assertThat(answers.ask(caller, conversation, "Another?", DataClass.PUBLIC)).isEqualTo(new Start.Busy());

            assertThat(answers.cancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            awaitEnd(started);

            Message answer = answerOf(started);
            assertThat(answer.status()).isEqualTo(Status.CANCELLED);
            assertThat(answer.text()).isEmpty();
            assertThat(answer.usage().outputTokens()).isZero();
            Started next = started(answers.ask(caller, conversation, "[fake pieces=1 delay=1ms] Next", DataClass.PUBLIC));
            awaitEnd(next);
            assertThat(answerOf(next).status()).isEqualTo(Status.COMPLETED);
        }
    }

    /**
     * The writer goes wrong after the request, as when the database goes away for a write: the model's usage goes with
     * the failure, and the answer takes no ending but message.cancelled, so the writer writes nothing and leaves the
     * answer to a reader once its lease runs out (M2-11).
     */
    @Test
    void aWriterThatGoesWrongAfterARequestLeavesTheAnswerToALaterReader() throws Exception {
        AtomicInteger deltas = new AtomicInteger();
        Messages failingOnSecondDelta = (Messages) Proxy.newProxyInstance(Messages.class.getClassLoader(),
                new Class<?>[] {Messages.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("addDelta") && deltas.incrementAndGet() == 2) {
                        throw new IllegalStateException("The test's database went away");
                    }
                    try {
                        return method.invoke(messages, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelPort twoPieces = (request, output) -> {
            output.text("one ");
            written.countDown();
            await(release);
            output.text("two ");
            return new ModelPort.Completed(new ModelPort.Usage(3, 8));
        };
        try (GeneratingAnswers writer = new GeneratingAnswers(failingOnSecondDelta, stub(twoPieces))) {
            Started started = started(writer.ask(caller, conversation, "Two?", DataClass.PUBLIC));
            assertThat(written.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            waitForEvents(started, 2);

            assertThat(messages.requestCancel(caller, conversation, idOf(started))).isEqualTo(new CancelRequest.Recorded());
            release.countDown();
            awaitEnd(started);

            assertThat(answerOf(started).status()).isEqualTo(Status.IN_PROGRESS);
            assertThat(eventsOf(started)).extracting(Event::type)
                    .containsExactly(EventType.MESSAGE_STARTED, EventType.MESSAGE_DELTA);
        }
    }
}
