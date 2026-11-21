package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.gateway.api.Gateway;
import aie.core.gateway.api.Gateway.Completed;
import aie.core.gateway.api.Gateway.Request;
import aie.core.gateway.api.Gateway.Result;
import aie.core.gateway.api.Gateway.Role;
import aie.core.gateway.api.Gateway.Turn;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.Answers;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A-14 on a real database: the gateway that {@link Models} builds asks a real pool whether a call comes from inside a
 * transaction. The pool has one connection, so a transaction that kept its connection would hold up the next one.
 */
@Testcontainers
class GatewayInTransactionTest {

    private static final String TENANT = "ten_a";
    private static final String REFUSAL = "No model is called inside a database transaction (A-14), so end it first";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgPool pool;
    private static Gateway gateway;
    /** The gateway's receipt for the question, checked outside any transaction (M3-4-1). */
    private static Gateway.Checked hello;

    /** The pieces the caller got in this test. */
    private final List<String> pieces = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createRolesAndOpenAPoolOfOne() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 1);
        gateway = Models.gateway(RegionPacks.read("jp"), (entry, content) -> { }, use -> { }, pool::insideTx);
        hello = (Gateway.Checked) gateway.checkInput(TestApi.CALLER, Answers.TASK, "Hello");
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    private Result ask() {
        return gateway.answer(
                new Request(Answers.TASK, List.of(new Turn(Role.USER, "Hello")), () -> false, DataClass.PUBLIC, hello, "msg_1"), pieces::add);
    }

    /** M3-4-1: the input check may come to call a service, so it is refused inside a transaction too. */
    @Test
    void aCheckFromInsideATransactionIsRefused() {
        assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> gateway.checkInput(TestApi.CALLER, Answers.TASK, "Hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No text is checked inside a database transaction (A-14), so end it first");
        assertThatThrownBy(() -> pool.inSystemTx(tx -> gateway.checkInput(TestApi.CALLER, Answers.TASK, "Hello")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gateway.checkInput(TestApi.CALLER, Answers.TASK, "Hello")).isInstanceOf(Gateway.Checked.class);
    }

    @Test
    void aCallFromInsideATenantTransactionGetsNoAnswer() {
        assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> ask()))
                .isInstanceOf(IllegalStateException.class).hasMessage(REFUSAL);
        assertThat(pieces).isEmpty();
    }

    @Test
    void aCallFromInsideASystemTransactionGetsNoAnswer() {
        assertThatThrownBy(() -> pool.inSystemTx(tx -> ask()))
                .isInstanceOf(IllegalStateException.class).hasMessage(REFUSAL);
        assertThat(pieces).isEmpty();
    }

    @Test
    void theSameThreadIsAnsweredOnceTheTransactionHasEnded() {
        assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> ask())).isInstanceOf(IllegalStateException.class);
        pool.inTenantTx(TENANT, tx -> 1);

        Result result = ask();

        assertThat(result).isInstanceOf(Completed.class);
        assertThat(pieces).hasSize(8);
    }

    /**
     * Requests run side by side, so a transaction on one thread must not stop an answer on another. That thread holds
     * the only connection, which also shows that asking needs none.
     */
    @Test
    void aTransactionOnAnotherThreadDoesNotStopThisOne() throws Exception {
        CompletableFuture<Void> opened = new CompletableFuture<>();
        CompletableFuture<Void> answered = new CompletableFuture<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> other = threads.submit(() -> pool.inTenantTx(TENANT, tx -> {
                opened.complete(null);
                answered.orTimeout(30, TimeUnit.SECONDS).join();
                return 1;
            }));
            opened.get(30, TimeUnit.SECONDS);
            Result result = ask();
            answered.complete(null);

            assertThat(result).isInstanceOf(Completed.class);
            assertThat(pieces).hasSize(8);
            assertThat(other.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    /** A refused transaction rolls back and returns the only connection, so the next one starts at once. */
    @Test
    void aRefusedCallGivesItsConnectionBack() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> ask())).isInstanceOf(IllegalStateException.class);
        }

        String tenant = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> pool.inTenantTx(TENANT,
                tx -> tx.query("SELECT current_setting('app.tenant_id', true) AS value", row -> row.string("value")).get(0)));

        assertThat(tenant).isEqualTo(TENANT);
    }
}
