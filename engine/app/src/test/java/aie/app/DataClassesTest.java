package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.Ids;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.Applications;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseApplications;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Asked;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The data class of a call (blueprint 7.3, M3-3-1), on the real scripts, as engine_app. A call starts at its
 * application's default class, which the caller can raise; the conversation's watermark, the highest class it has sent to
 * a model, raises it further. Both messages of the call keep its class, and the watermark rises to it in the same
 * transaction. The watermark never goes down, and a stored message never changes its class.
 */
@Testcontainers
class DataClassesTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
    private static Messages messages;
    private static Conversations conversations;
    private static EndUsers endUsers;
    private static Provisioning provisioning;
    private static Applications applications;

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
        applications = new DatabaseApplications(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.conversation_conversations CASCADE");
    }

    /** A caller of a new tenant's application with this default class; the key ID plays no part here. */
    private static Owner newApplication(DataClass defaultClass) {
        String tenant = provisioning.createTenant("jp1", null);
        return new Owner("key_unused", tenant, provisioning.createApplication(tenant, "test", defaultClass.wireName()));
    }

    private static String newConversation(Owner caller) {
        return conversations.start(caller, endUsers.resolve(caller, "user-1"), null).conversationId();
    }

    private static Asked ask(Owner caller, String conversation, DataClass floor) {
        return TestApi.asked(messages.ask(caller, conversation, "Hello?", floor, DataClass.SENSITIVE));
    }

    /** Each message as its role and class, oldest first, whatever row-level security lets the engine see. */
    private static List<String> classes() throws SQLException {
        return TestDatabase.firstColumn(DATABASE,
                "SELECT role || ' ' || data_class FROM engine.conversation_messages ORDER BY ordinal");
    }

    private static String watermark(String conversation) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT data_class_watermark FROM engine.conversation_conversations"
                + " WHERE conversation_id = '" + conversation + "'").getFirst();
    }

    /** Sets the watermark as engine_app, in the caller's tenant, and says how many conversations it changed. */
    private static int setWatermark(Owner caller, String conversation, String dataClass) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.update(
                "UPDATE conversation_conversations SET data_class_watermark = ? WHERE conversation_id = ?", dataClass, conversation));
    }

    @Test
    void aCallTakesTheHigherOfItsFloorAndTheWatermark() throws SQLException {
        Owner caller = newApplication(DataClass.INTERNAL);
        String conversation = newConversation(caller);
        assertThat(watermark(conversation)).isEqualTo("public");

        Asked first = ask(caller, conversation, DataClass.INTERNAL);
        String afterFirst = watermark(conversation);
        Asked raised = ask(caller, conversation, DataClass.SENSITIVE);
        String afterRaised = watermark(conversation);
        Asked kept = ask(caller, conversation, DataClass.PUBLIC);

        assertThat(first.dataClass()).isEqualTo(DataClass.INTERNAL);
        assertThat(afterFirst).isEqualTo("internal");
        assertThat(raised.dataClass()).isEqualTo(DataClass.SENSITIVE);
        assertThat(afterRaised).isEqualTo("sensitive");
        assertThat(kept.dataClass()).isEqualTo(DataClass.SENSITIVE);
        assertThat(watermark(conversation)).isEqualTo("sensitive");
        assertThat(classes()).containsExactly("user internal", "assistant internal", "user sensitive", "assistant sensitive",
                "user sensitive", "assistant sensitive");
    }

    static Stream<Arguments> everyPair() {
        return Arrays.stream(DataClass.values())
                .flatMap(held -> Arrays.stream(DataClass.values()).map(floor -> arguments(held, floor)));
    }

    /** The classes go public, internal, personal, sensitive, in Java and in the database alike. */
    @ParameterizedTest
    @MethodSource("everyPair")
    void theHigherClassCounts(DataClass held, DataClass floor) throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        ask(caller, conversation, held);

        Asked asked = ask(caller, conversation, floor);

        DataClass higher = floor.ordinal() > held.ordinal() ? floor : held;
        assertThat(asked.dataClass()).isEqualTo(higher);
        assertThat(watermark(conversation)).isEqualTo(higher.wireName());
        assertThat(classes()).containsExactly("user " + held.wireName(), "assistant " + held.wireName(),
                "user " + higher.wireName(), "assistant " + higher.wireName());
    }

    /** Blueprint 7.3: what a conversation has sent to a model stays with it, so not even the owner can lower its watermark. */
    @Test
    void theWatermarkNeverGoesDown() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        ask(caller, conversation, DataClass.PERSONAL);

        for (String lower : List.of("internal", "public")) {
            assertThatThrownBy(() -> setWatermark(caller, conversation, lower)).isInstanceOf(DatabaseException.class)
                    .hasStackTraceContaining("Conversation " + conversation
                            + " has sent personal data to a model, so its watermark cannot go down to " + lower);
        }
        assertThatThrownBy(() -> TestDatabase.asAdmin(DATABASE, "UPDATE engine.conversation_conversations"
                        + " SET data_class_watermark = 'public' WHERE conversation_id = '" + conversation + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("so its watermark cannot go down to public");
        assertThat(watermark(conversation)).isEqualTo("personal");

        assertThat(setWatermark(caller, conversation, "personal")).isOne();
        assertThat(setWatermark(caller, conversation, "sensitive")).isOne();
        assertThat(watermark(conversation)).isEqualTo("sensitive");
    }

    /** A message keeps the class it was stored with, ended or not: V3 refuses every change but an event's. */
    @Test
    void aStoredMessageKeepsItsClass() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        Asked asked = ask(caller, conversation, DataClass.PERSONAL);

        assertThatThrownBy(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(
                        "UPDATE conversation_messages SET data_class = 'public' WHERE message_id = ?", asked.question().messageId())))
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("A message changes only through its events");
        messages.addDelta(caller, conversation, asked.answer().messageId(), "Yours.");
        messages.complete(caller, conversation, asked.answer().messageId(), new Usage(1, 1));

        assertThat(classes()).containsExactly("user personal", "assistant personal");
    }

    /** Only the four classes can be stored, and a message is stored only with its class. */
    @Test
    void aMessageIsStoredOnlyWithOneOfTheFourClasses() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        String insert = """
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, text, completed_at%s)
                VALUES (?, ?, ?, 'user', 'completed', 'Hi', now()%s)
                """;

        assertThatThrownBy(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(insert.formatted(", data_class", ", 'secret'"),
                        Ids.next("msg"), caller.tenantId(), conversation)))
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("conversation_messages_data_class_check");
        assertThatThrownBy(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update(insert.formatted("", ""),
                        Ids.next("msg"), caller.tenantId(), conversation)))
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("null value in column \"data_class\"");
        assertThatThrownBy(() -> setWatermark(caller, conversation, "secret")).isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("conversation_conversations_data_class_watermark_check");
        assertThat(classes()).isEmpty();
        assertThat(watermark(conversation)).isEqualTo("public");
    }

    @Test
    void anAskNeedsItsFloor() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);

        assertThatThrownBy(() -> messages.ask(caller, conversation, "Hello?", null, DataClass.SENSITIVE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An ask needs its least data class, the application's default at the least");
        assertThat(classes()).isEmpty();
    }

    @Test
    void anAskNeedsItsCeiling() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);

        assertThatThrownBy(() -> messages.ask(caller, conversation, "Hello?", DataClass.PUBLIC, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An ask needs the ceiling of its task, the highest class its models take");
        assertThat(classes()).isEmpty();
    }

    /**
     * M3-3-2: a call above its task's ceiling has no model to go to, so it stores nothing and leaves the watermark as it
     * was (blueprint 7.3: what no model was sent neither joins the history nor raises the watermark). One at the ceiling
     * or below is stored.
     */
    @ParameterizedTest
    @MethodSource("everyPair")
    void aCallAboveTheCeilingStoresNothing(DataClass floor, DataClass ceiling) throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        boolean above = floor.ordinal() > ceiling.ordinal();

        Messages.Ask ask = messages.ask(caller, conversation, "Hello?", floor, ceiling);

        if (above) {
            assertThat(ask).isEqualTo(new Messages.Ask.AboveCeiling(floor));
            assertThat(classes()).isEmpty();
        } else {
            assertThat(TestApi.asked(ask).dataClass()).isEqualTo(floor);
            assertThat(classes()).containsExactly("user " + floor.wireName(), "assistant " + floor.wireName());
        }
        assertThat(watermark(conversation)).isEqualTo(above ? "public" : floor.wireName());
    }

    /** M3-3-2: the watermark counts against the ceiling, so after a sensitive call even a public one has no model. */
    @Test
    void theWatermarkCountsAgainstTheCeiling() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        ask(caller, conversation, DataClass.SENSITIVE);

        Messages.Ask ask = messages.ask(caller, conversation, "Hello?", DataClass.PUBLIC, DataClass.PERSONAL);

        assertThat(ask).isEqualTo(new Messages.Ask.AboveCeiling(DataClass.SENSITIVE));
        assertThat(classes()).containsExactly("user sensitive", "assistant sensitive");
        assertThat(watermark(conversation)).isEqualTo("sensitive");
    }

    /**
     * M3-3-2: a conversation out of reach is not found, whatever the class of the call or of the conversation, so another
     * application learns nothing of it, its class included.
     */
    @Test
    void aConversationOutOfReachIsNotFoundAboveTheCeilingToo() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        ask(caller, conversation, DataClass.SENSITIVE);
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "public"));
        Owner otherTenant = new Owner("key_unused", newApplication(DataClass.PUBLIC).tenantId(), caller.applicationId());

        assertThat(messages.ask(otherApplication, conversation, "Hello?", DataClass.PUBLIC, DataClass.PUBLIC))
                .isEqualTo(new Messages.Ask.NotFound());
        assertThat(messages.ask(otherTenant, conversation, "Hello?", DataClass.SENSITIVE, DataClass.PUBLIC))
                .isEqualTo(new Messages.Ask.NotFound());
        assertThat(messages.ask(caller, "conv_00000000000000000000000000", "Hello?", DataClass.SENSITIVE, DataClass.PUBLIC))
                .isEqualTo(new Messages.Ask.NotFound());
        assertThat(classes()).containsExactly("user sensitive", "assistant sensitive");
    }

    /** An ask that stores nothing raises nothing: neither one out of reach, nor one that fails partway. */
    @Test
    void anAskThatStoresNothingRaisesNoWatermark() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        Owner otherApplication = new Owner("key_unused", caller.tenantId(),
                provisioning.createApplication(caller.tenantId(), "test", "public"));
        Owner otherTenant = new Owner("key_unused", newApplication(DataClass.PUBLIC).tenantId(), caller.applicationId());

        assertThat(messages.ask(otherApplication, conversation, "Hello?", DataClass.SENSITIVE, DataClass.SENSITIVE)).isEqualTo(new Messages.Ask.NotFound());
        assertThat(messages.ask(otherTenant, conversation, "Hello?", DataClass.SENSITIVE, DataClass.SENSITIVE)).isEqualTo(new Messages.Ask.NotFound());
        TestDatabase.asAdmin(DATABASE, """
                CREATE FUNCTION engine.test_refuse_start() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'The test refuses message.started';
                END
                $$;
                CREATE TRIGGER test_refuse_start BEFORE INSERT ON engine.conversation_events
                    FOR EACH ROW WHEN (NEW.type = 'message.started') EXECUTE FUNCTION engine.test_refuse_start();
                """);
        try {
            assertThatThrownBy(() -> messages.ask(caller, conversation, "Hello?", DataClass.SENSITIVE, DataClass.SENSITIVE))
                    .isInstanceOf(DatabaseException.class).hasStackTraceContaining("The test refuses message.started");
        } finally {
            TestDatabase.asAdmin(DATABASE, """
                    DROP TRIGGER test_refuse_start ON engine.conversation_events;
                    DROP FUNCTION engine.test_refuse_start();
                    """);
        }

        assertThat(watermark(conversation)).isEqualTo("public");
        assertThat(classes()).isEmpty();
    }

    /** A message stored on its own, outside an ask, takes the watermark as its class too. */
    @Test
    void aMessageStoredOnItsOwnTakesTheWatermark() throws SQLException {
        Owner caller = newApplication(DataClass.PUBLIC);
        String conversation = newConversation(caller);
        ask(caller, conversation, DataClass.PERSONAL);

        messages.addUserMessage(caller, conversation, "More?").orElseThrow();
        messages.startAssistantMessage(caller, conversation).orElseThrow();

        assertThat(classes()).containsExactly("user personal", "assistant personal", "user personal", "assistant personal");
    }

    /** The default class is read in the caller's tenant for each call, so a change applies to the next one. */
    @Test
    void anApplicationGivesItsDefaultClass() throws SQLException {
        for (DataClass dataClass : DataClass.values()) {
            assertThat(applications.defaultDataClass(newApplication(dataClass))).isEqualTo(dataClass);
        }
        Owner caller = newApplication(DataClass.INTERNAL);

        TestDatabase.asAdmin(DATABASE, "UPDATE engine.tenancy_applications SET default_data_class = 'personal'"
                + " WHERE application_id = '" + caller.applicationId() + "'");

        assertThat(applications.defaultDataClass(caller)).isEqualTo(DataClass.PERSONAL);
    }

    /** Row-level security keeps another tenant's application out of reach, as if it did not exist. */
    @Test
    void anotherTenantsApplicationHasNoDefaultClassForTheCaller() {
        Owner owner = newApplication(DataClass.SENSITIVE);
        Owner otherTenant = new Owner("key_unused", newApplication(DataClass.PUBLIC).tenantId(), owner.applicationId());

        assertThatThrownBy(() -> applications.defaultDataClass(otherTenant)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The application " + owner.applicationId() + " of tenant " + otherTenant.tenantId() + " does not exist");
    }
}
