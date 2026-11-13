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
import aie.modules.conversation.api.Conversations.Conversation;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Conversations on the real scripts of both modules, as engine_app, with end users that tenancy made. */
@Testcontainers
class ConversationsTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
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

    private static Owner anotherApplicationOf(Owner caller) {
        return new Owner("key_unused", caller.tenantId(), provisioning.createApplication(caller.tenantId(), "test", "internal"));
    }

    private static List<String> rows() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT tenant_id || ' ' || application_id || ' ' || end_user_id || ' ' || coalesce(locale, '-')
                FROM engine.conversation_conversations ORDER BY created_at
                """);
    }

    @Test
    void aConversationIsFoundAsItStarted() {
        Owner caller = newApplication();
        String endUser = endUsers.resolve(caller, "user-1");

        Conversation started = conversations.start(caller, endUser, "ja-JP");

        assertThat(started.conversationId()).matches("conv_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(started.endUserId()).isEqualTo(endUser);
        assertThat(started.locale()).isEqualTo("ja-JP");
        assertThat(started.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(conversations.find(caller, started.conversationId())).contains(started);
    }

    @Test
    void theRowKeepsTheTenantTheApplicationAndTheEndUser() throws SQLException {
        Owner caller = newApplication();
        String endUser = endUsers.resolve(caller, "user-1");

        conversations.start(caller, endUser, "en");
        conversations.start(caller, endUser, null);

        assertThat(rows()).containsExactly(
                caller.tenantId() + " " + caller.applicationId() + " " + endUser + " en",
                caller.tenantId() + " " + caller.applicationId() + " " + endUser + " -");
    }

    @Test
    void aConversationWithoutALocaleHasNone() {
        Owner caller = newApplication();

        Conversation started = conversations.start(caller, endUsers.resolve(caller, "user-1"), null);

        assertThat(started.locale()).isNull();
        assertThat(conversations.find(caller, started.conversationId())).hasValueSatisfying(found -> assertThat(found.locale()).isNull());
    }

    @Test
    void eachConversationHasAnIdOfItsOwn() {
        Owner caller = newApplication();
        String endUser = endUsers.resolve(caller, "user-1");

        Conversation first = conversations.start(caller, endUser, null);
        Conversation second = conversations.start(caller, endUser, null);

        assertThat(first.conversationId()).isNotEqualTo(second.conversationId());
        assertThat(conversations.find(caller, first.conversationId())).contains(first);
        assertThat(conversations.find(caller, second.conversationId())).contains(second);
    }

    /** Keys and end users are per application, so another application of the same tenant does not see it. */
    @Test
    void anotherApplicationOfTheTenantDoesNotFindIt() {
        Owner caller = newApplication();
        Conversation started = conversations.start(caller, endUsers.resolve(caller, "user-1"), null);

        assertThat(conversations.find(anotherApplicationOf(caller), started.conversationId())).isEmpty();
    }

    /** Row-level security hides it even from a caller that names the same application. */
    @Test
    void anotherTenantDoesNotFindIt() {
        Owner caller = newApplication();
        Conversation started = conversations.start(caller, endUsers.resolve(caller, "user-1"), null);
        Owner stranger = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        assertThat(conversations.find(stranger, started.conversationId())).isEmpty();
    }

    @Test
    void anUnknownOrMalformedIdIsNotFound() {
        Owner caller = newApplication();
        conversations.start(caller, endUsers.resolve(caller, "user-1"), null);

        assertThat(conversations.find(caller, "conv_00000000000000000000000000")).isEmpty();
        assertThat(conversations.find(caller, "not an id")).isEmpty();
        assertThat(conversations.find(caller, null)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "user-1", "eu_", "eu_a-b", "conv_abc"})
    void aConversationNeedsAWellFormedEndUserId(String endUserId) throws SQLException {
        Owner caller = newApplication();

        assertThatThrownBy(() -> conversations.start(caller, endUserId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isEmpty();
    }

    /** The session token's rule: a well-formed tag of at most 35 characters, as its standard form writes it. */
    @ParameterizedTest
    @ValueSource(strings = {"ja-jp", "iw", "", "not a tag", "en-Latn-US-u-ca-gregory-co-phonebk-x-a"})
    void aLocaleMustBeAWellFormedTagInStandardForm(String locale) throws SQLException {
        Owner caller = newApplication();
        String endUser = endUsers.resolve(caller, "user-1");

        assertThatThrownBy(() -> conversations.start(caller, endUser, locale)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isEmpty();
    }

    /** The table keeps the contract's limit of 1 to 35 characters itself, for SQL that goes around this class. */
    @Test
    void theTableRefusesALocaleOutsideTheLimits() {
        Owner caller = newApplication();
        String add = """
                INSERT INTO conversation_conversations (conversation_id, tenant_id, application_id, end_user_id, locale)
                VALUES (?, ?, ?, 'eu_1', ?)
                """;

        for (String locale : List.of("", "a".repeat(36))) {
            assertThatThrownBy(() -> database.inTenantTx(caller.tenantId(),
                    tx -> tx.update(add, "conv_" + locale.length(), caller.tenantId(), caller.applicationId(), locale)))
                    .as("a locale of %d characters", locale.length()).isInstanceOf(DatabaseException.class);
        }
        int added = database.inTenantTx(caller.tenantId(),
                tx -> tx.update(add, "conv_35", caller.tenantId(), caller.applicationId(), "a".repeat(35)));
        assertThat(added).isOne();
    }
}
