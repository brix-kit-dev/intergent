package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.modelfake.FakeModel;
import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Route;
import aie.core.gateway.internal.RoutingGateway;
import aie.core.metering.internal.DatabaseMetering;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import aie.core.trust.internal.DatabaseAudit;
import aie.http.EventStreamProbe;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Answers.Start.Started;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.modules.conversation.internal.GeneratingAnswers;
import aie.modules.conversation.internal.LocalEventSignals;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real event stream on the real scripts: it follows an answer that the real generator and the fake model write
 * while it reads, replays one that has ended, and ends one whose writer has gone.
 */
@Testcontainers
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EventStreamDatabaseTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgPool pool;
    private static Messages messages;
    private static GeneratingAnswers answers;
    private static Owner caller;
    private static String conversation;

    @BeforeAll
    static void migrateAndStartAConversation() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 4);
        messages = new DatabaseMessages(pool);
        answers = new GeneratingAnswers(messages, new DatabaseConversations(pool), new RoutingGateway(Map.of(FakeModel.MODEL, new RoutingGateway.Model(new FakeModel(), DataClass.SENSITIVE, ModelSpec.Price.FREE)),
                Map.of(Answers.TASK, new Route(List.of(FakeModel.MODEL), Duration.ofSeconds(30), CheckMode.SENTENCE, TestApi.prompts(List.of(FakeModel.MODEL)))), Models.checks(), new DatabaseAudit(pool, TestApi.AUDIT_MASTER_KEY), new DatabaseMetering(pool), TestApi.budgets(pool), pool::insideTx));
        DatabaseProvisioning provisioning = new DatabaseProvisioning(pool);
        String tenant = provisioning.createTenant("jp1", null);
        caller = new Owner("key_unused", tenant, provisioning.createApplication(tenant, "test", "internal"));
        conversation = new DatabaseConversations(pool).start(caller, new DatabaseEndUsers(pool).resolve(caller, "user-1"), null)
                .conversationId();
    }

    @AfterAll
    static void close() {
        answers.close();
        pool.close();
    }

    private static Started ask(String text) {
        return (Started) answers.ask(caller, conversation, text, DataClass.PUBLIC);
    }

    /** Each event's id and type, from the SSE text. */
    private static List<String> idsAndTypes(String sent) {
        return Arrays.stream(sent.split("\n\n"))
                .map(event -> event.lines().filter(line -> line.startsWith("id: ") || line.startsWith("event: "))
                        .map(line -> line.substring(line.indexOf(' ') + 1)).reduce((id, type) -> id + " " + type).orElse(""))
                .toList();
    }

    /** The stream starts while the model still writes, so it waits on the log and follows it to the ending. */
    @Test
    void theStreamFollowsAnAnswerWhileItIsWritten() {
        Started started = ask("[fake pieces=3 delay=150ms] Hello");

        String sent = EventStreamProbe.send(messages, caller, conversation, started.asked().answer().messageId(), 0);

        assertThat(idsAndTypes(sent)).containsExactly("1 message.started", "2 message.delta", "3 message.delta",
                "4 message.delta", "5 message.completed");
        assertThat(sent).contains("\"text\":\"Piece 1 of 3. \"", "\"text\":\"Piece 3 of 3. \"");
    }

    /**
     * M2-7-4 on the real scripts: the messages that the generator writes through ring the signals the stream waits on, so
     * with a look at the log only every 10 seconds the stream still follows the answer as it is written.
     */
    @Test
    void theSignalsOfTheWritesWakeTheStream() {
        LocalEventSignals signals = new LocalEventSignals();
        Messages ringing = new DatabaseMessages(pool, signals);
        try (GeneratingAnswers ringingAnswers = new GeneratingAnswers(ringing, new DatabaseConversations(pool), new RoutingGateway(Map.of(FakeModel.MODEL, new RoutingGateway.Model(new FakeModel(), DataClass.SENSITIVE, ModelSpec.Price.FREE)),
                Map.of(Answers.TASK, new Route(List.of(FakeModel.MODEL), Duration.ofSeconds(30), CheckMode.SENTENCE, TestApi.prompts(List.of(FakeModel.MODEL)))), Models.checks(), new DatabaseAudit(pool, TestApi.AUDIT_MASTER_KEY), new DatabaseMetering(pool), TestApi.budgets(pool), pool::insideTx))) {
            Started started = (Started) ringingAnswers.ask(caller, conversation, "[fake pieces=3 delay=200ms] Quick", DataClass.PUBLIC);

            long start = System.nanoTime();
            String sent = EventStreamProbe.send(ringing, signals, Duration.ofSeconds(10), caller, conversation,
                    started.asked().answer().messageId(), 0);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(idsAndTypes(sent)).containsExactly("1 message.started", "2 message.delta", "3 message.delta",
                    "4 message.delta", "5 message.completed");
            assertThat(signals.waiting()).isZero();
        }
    }

    @Test
    void anAnswerThatHasEndedIsSentWholeAtOnce() throws Exception {
        Started started = ask("[fake pieces=2 delay=1ms] Hi");
        started.ended().toCompletableFuture().get(20, TimeUnit.SECONDS);

        long start = System.nanoTime();
        String sent = EventStreamProbe.send(messages, caller, conversation, started.asked().answer().messageId(), 0);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(idsAndTypes(sent)).containsExactly("1 message.started", "2 message.delta", "3 message.delta",
                "4 message.completed");
    }

    /**
     * M2-11-3: an answer whose writer has gone, as if its instance died, so that nothing renews its lease, which has run
     * out. The stream sends what the log holds, and after 5 seconds with nothing new it looks at the lease: the stream
     * ends the answer for its writer as interrupted, and sends that ending like any other.
     */
    @Test
    void aStreamEndsTheAnswerOfAWriterThatHasGone() throws SQLException {
        String alone = new DatabaseConversations(pool).start(caller, new DatabaseEndUsers(pool).resolve(caller, "user-1"), null)
                .conversationId();
        String answer = TestApi.asked(messages.ask(caller, alone, "Anyone there?", DataClass.PUBLIC, DataClass.SENSITIVE)).answer().messageId();
        assertThat(messages.addDelta(caller, alone, answer, "Hal")).isEqualTo(new Messages.Written.Added(2));
        TestDatabase.asAdmin(DATABASE, "UPDATE engine.conversation_answer_leases SET expires_at = now() - interval '1 second'"
                + " WHERE message_id = '" + answer + "'");

        long start = System.nanoTime();
        String sent = EventStreamProbe.send(messages, caller, alone, answer, 0);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(20));
        assertThat(idsAndTypes(sent)).containsExactly("1 message.started", "2 message.delta", "3 error");
        assertThat(sent).contains("\"code\":\"stream_interrupted\"", "\"status\":503");
        Messages.Message ended = messages.find(caller, alone, answer).orElseThrow();
        assertThat(ended.status()).isEqualTo(Messages.Status.FAILED);
        assertThat(ended.code()).isEqualTo(Messages.ErrorCode.STREAM_INTERRUPTED);
    }

    @Test
    void aFailedAnswerEndsItsStreamWithItsError() throws Exception {
        Started started = ask("[fake pieces=3 fail-after=1 delay=1ms] Fail");
        started.ended().toCompletableFuture().get(20, TimeUnit.SECONDS);

        String sent = EventStreamProbe.send(messages, caller, conversation, started.asked().answer().messageId(), 1);

        assertThat(idsAndTypes(sent)).containsExactly("2 message.delta", "3 error");
        assertThat(sent).contains("\"code\":\"model_failed\"", "\"status\":502");
    }
}
