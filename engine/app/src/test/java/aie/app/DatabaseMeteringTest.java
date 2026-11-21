package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.metering.api.Metering;
import aie.core.metering.internal.DatabaseMetering;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M3-6-1: metering on the real scripts, as engine_app through a pool. Each use is kept as it came, its cost the exact
 * decimal it was given, and nothing engine_app may do changes, deletes or locks a use once recorded. That each tenant
 * reads only its own uses, TenantIsolationTest shows for every tenant table.
 */
@Testcontainers
class DatabaseMeteringTest {

    private static final String TENANT = "ten_a";
    /** Each use as one line, in the order it was recorded. */
    private static final String USES = """
            SELECT concat_ws(' ', tenant_id, coalesce(request_id, '-'), application_id, message_id, task, model, input_tokens,
                output_tokens, cost_usd)
            FROM engine.metering_usage ORDER BY created_at, use_id
            """;

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgPool pool;
    private static Metering metering;

    @BeforeAll
    static void createRolesMigrateAndOpenAPool() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 2);
        metering = new DatabaseMetering(pool);
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    @BeforeEach
    void emptyTheUsage() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.metering_usage");
    }

    private static Metering.Use use(String requestId, long in, long out, String cost) {
        return new Metering.Use(requestId, TENANT, "app_a", "msg_a", "customer_chat", "fake", in, out, new BigDecimal(cost));
    }

    /**
     * Each use is kept as it came, with an ID of its own and the time the database recorded it, and its cost exactly as
     * it was given, however many places it has: a sum of costs in the database is the sum of what the calls cost.
     */
    @Test
    void eachUseIsKeptAsItCameAndItsCostExactly() throws SQLException {
        metering.record(use("req_1", 1234, 567, "0.012207"));
        metering.record(use(null, 1, 1, "0.000000375"));
        metering.record(use("req_3", 0, 0, "0"));
        metering.record(use("req_4", 9_000_000_000_000L, 8_000_000_000_000L, "147000000.000000"));

        assertThat(TestDatabase.firstColumn(DATABASE, USES)).containsExactly(
                "ten_a req_1 app_a msg_a customer_chat fake 1234 567 0.012207",
                "ten_a - app_a msg_a customer_chat fake 1 1 0.000000375",
                "ten_a req_3 app_a msg_a customer_chat fake 0 0 0",
                "ten_a req_4 app_a msg_a customer_chat fake 9000000000000 8000000000000 147000000.000000");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT sum(cost_usd) || ' ' || count(DISTINCT use_id) || ' '"
                + " || bool_and(use_id ~ '^use_[0-9A-Z]{26}$') || ' ' || bool_and(created_at > now() - interval '1 minute')"
                + " FROM engine.metering_usage")).containsExactly("147000000.012207375 4 true true");
    }

    /** The table refuses a use below 0 tokens or a cost below nothing, and one without who pays or what was called. */
    @Test
    void aUseThatDoesNotFitIsRefused() throws SQLException {
        String add = "INSERT INTO metering_usage (use_id, tenant_id, application_id, message_id, task, model, input_tokens,"
                + " output_tokens, cost_usd) VALUES ('use_x', 'ten_a', %s, 'msg_a', 'customer_chat', 'fake', %s, %s, %s)";
        Map<String, String> refusals = Map.of(
                add.formatted("'app_a'", "-1", "0", "0"), "metering_usage_input_tokens_check",
                add.formatted("'app_a'", "0", "-1", "0"), "metering_usage_output_tokens_check",
                add.formatted("'app_a'", "0", "0", "-0.000001"), "metering_usage_cost_usd_check",
                add.formatted("NULL", "0", "0", "0"), "null value in column \"application_id\"");
        refusals.forEach((sql, refusal) -> assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> tx.update(sql))).as(sql)
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining(refusal));

        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.metering_usage")).containsExactly("0");
    }

    /**
     * A use is only recorded: engine_app may read and add uses, and every way it might change, delete or lock one is
     * refused, by the table's grants or because it does not own the table. The use stays as it was.
     */
    @Test
    void aUseCannotBeChangedDeletedOrLocked() throws SQLException {
        metering.record(use("req_1", 5, 2, "0.000045"));
        List<String> before = TestDatabase.firstColumn(DATABASE, USES);
        Map<String, String> refusals = Map.of(
                "UPDATE metering_usage SET cost_usd = 0", "permission denied",
                "DELETE FROM metering_usage", "permission denied",
                "TRUNCATE metering_usage", "permission denied",
                "SELECT use_id FROM metering_usage FOR UPDATE", "permission denied",
                "ALTER TABLE metering_usage DISABLE ROW LEVEL SECURITY", "must be owner",
                "DROP TABLE metering_usage", "must be owner");

        refusals.forEach((sql, refusal) -> assertThatThrownBy(() -> pool.inTenantTx(TENANT, tx -> sql.startsWith("SELECT")
                        ? tx.query(sql, row -> row.string("use_id")).size() : tx.update(sql)))
                .as(sql).isInstanceOf(DatabaseException.class).hasStackTraceContaining(refusal));

        assertThat(TestDatabase.firstColumn(DATABASE, USES)).isEqualTo(before);
    }
}
