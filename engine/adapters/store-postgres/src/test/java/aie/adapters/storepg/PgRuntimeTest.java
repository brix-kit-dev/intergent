package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.ports.DatabaseException;
import aie.ports.DatabasePort;
import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PgRuntimeTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRoles() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
    }

    private static String currentUser(DatabasePort database) {
        return database.inSystemTx(tx -> tx.query("SELECT current_user AS who", row -> row.string("who")).get(0));
    }

    private static String setting(DatabasePort database, String name) {
        return database.inSystemTx(tx -> tx.query("SELECT current_setting(?, true) AS value", row -> row.string("value"), name).get(0));
    }

    @Test
    void itConnectsAsEngineApp() {
        DatabasePort database = PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD);

        assertThat(currentUser(database)).isEqualTo("engine_app");
    }

    @Test
    void theAddressCannotChooseAnotherRole() {
        DatabasePort database = PgRuntime.connect(DATABASE.getJdbcUrl() + "&user=postgres", TestDatabase.APP_PASSWORD);

        assertThat(currentUser(database)).isEqualTo("engine_app");
    }

    /** A default tenant in the address would give every transaction without a tenant one after all. */
    @Test
    void theAddressCannotSetSessionOptions() {
        DatabasePort database = PgRuntime.connect(
                DATABASE.getJdbcUrl() + "&options=-c%20app.tenant_id%3Dten_a%20-c%20row_security%3Doff", TestDatabase.APP_PASSWORD);

        assertThat(setting(database, "app.tenant_id")).isNull();
        assertThat(setting(database, "row_security")).isEqualTo("on");
    }

    @Test
    void aRoleThatFailsTheSelfCheckGetsNoConnection() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app BYPASSRLS");
        try {
            assertThatThrownBy(() -> PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD))
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("has BYPASSRLS");
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app NOBYPASSRLS");
        }
    }
}
