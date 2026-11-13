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
import aie.http.api.model.CreateMessageRequest;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Role;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabaseException;
import jakarta.validation.constraints.Size;
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

/** Messages on the real scripts of both modules, as engine_app, in conversations of real applications. */
@Testcontainers
class MessagesTest {

    /** A character outside the Basic Multilingual Plane: one character, two UTF-16 units, four bytes. */
    private static final String ASTRAL = "😀";

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

    private static Owner anotherApplicationOf(Owner caller) {
        return new Owner("key_unused", caller.tenantId(), provisioning.createApplication(caller.tenantId(), "test", "internal"));
    }

    private static String newConversation(Owner caller) {
        return conversations.start(caller, endUsers.resolve(caller, "user-1"), null).conversationId();
    }

    /** What is really in the table, whatever row-level security lets the engine see. */
    private static List<String> rows() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT conversation_id || ' ' || role || ' ' || status || ' ' || text
                FROM engine.conversation_messages ORDER BY ordinal
                """);
    }

    /** Adds a row as engine_app with SQL of its own, going around DatabaseMessages, so only the table's rules apply. */
    private static int addRow(Owner caller, String conversationId, String messageId, String values) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.update("""
                INSERT INTO conversation_messages
                    (message_id, tenant_id, conversation_id, role, status, text, input_tokens, output_tokens, code, completed_at)
                VALUES ('%s', '%s', '%s', %s)
                """.formatted(messageId, caller.tenantId(), conversationId, values)));
    }

    private static void assertRefused(Runnable write, String reason) {
        assertThatThrownBy(write::run).isInstanceOf(DatabaseException.class).hasStackTraceContaining(reason);
    }

    @Test
    void aUserMessageIsStoredWhole() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        Message added = messages.addUserMessage(caller, conversation, "Where is my parcel?").orElseThrow();

        assertThat(added.messageId()).matches("msg_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(added.conversationId()).isEqualTo(conversation);
        assertThat(added.role()).isEqualTo(Role.USER);
        assertThat(added.status()).isEqualTo(Status.COMPLETED);
        assertThat(added.text()).isEqualTo("Where is my parcel?");
        assertThat(added.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(added.completedAt()).isEqualTo(added.createdAt());
        assertThat(messages.find(caller, conversation, added.messageId())).contains(added);
    }

    @Test
    void anAnswerStartsInProgressAndEmpty() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        Message started = messages.startAssistantMessage(caller, conversation).orElseThrow();

        assertThat(started.messageId()).matches("msg_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(started.conversationId()).isEqualTo(conversation);
        assertThat(started.role()).isEqualTo(Role.ASSISTANT);
        assertThat(started.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(started.text()).isEmpty();
        assertThat(started.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(started.completedAt()).isNull();
        assertThat(messages.find(caller, conversation, started.messageId())).contains(started);
    }

    @Test
    void aConversationIsListedInTheOrderItsMessagesCameIn() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        Message question = messages.addUserMessage(caller, conversation, "Hello").orElseThrow();
        Message answer = messages.startAssistantMessage(caller, conversation).orElseThrow();
        Message followUp = messages.addUserMessage(caller, conversation, "Are you there?").orElseThrow();
        Message secondAnswer = messages.startAssistantMessage(caller, conversation).orElseThrow();

        assertThat(messages.list(caller, conversation)).containsExactly(question, answer, followUp, secondAnswer);
        assertThat(rows()).containsExactly(
                conversation + " user completed Hello",
                conversation + " assistant in_progress ",
                conversation + " user completed Are you there?",
                conversation + " assistant in_progress ");
    }

    /**
     * Each later row gets a smaller ID and an earlier time, so neither can give the order by chance. Then the first row is
     * written again, which moves it behind the others where they lie, so neither the order they lie in nor its reverse is
     * the right one. The index on the conversation holds the rows in order already, so the test turns index scans off for
     * engine_app: only ORDER BY can keep the order then, whatever plan the database picks.
     */
    @Test
    void theOrderIsTheDatabasesOwnNumberAndNotTheIdTheTimeOrThePlace() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String add = """
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, text, created_at, completed_at)
                VALUES (?, ?, ?, 'user', 'completed', ?, now() - ?::interval, now())
                """;
        database.inTenantTx(caller.tenantId(), tx -> tx.update(add, "msg_C", caller.tenantId(), conversation, "first", "0 hours"));
        database.inTenantTx(caller.tenantId(), tx -> tx.update(add, "msg_B", caller.tenantId(), conversation, "second", "1 hour"));
        database.inTenantTx(caller.tenantId(), tx -> tx.update(add, "msg_A", caller.tenantId(), conversation, "third", "2 hours"));

        TestDatabase.asAdmin(DATABASE, "UPDATE engine.conversation_messages SET text = text WHERE message_id = 'msg_C'");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_messages"))
                .as("where the rows lie").containsExactly("msg_B", "msg_A", "msg_C");

        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app SET enable_indexscan = off");
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app SET enable_bitmapscan = off");
        try {
            assertThat(messages.list(caller, conversation)).extracting(Message::messageId)
                    .containsExactly("msg_C", "msg_B", "msg_A");
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app RESET enable_indexscan");
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app RESET enable_bitmapscan");
        }
    }

    @Test
    void aMessageIsFoundOnlyThroughItsOwnConversation() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String other = newConversation(caller);
        Message added = messages.addUserMessage(caller, conversation, "Hello").orElseThrow();
        Message elsewhere = messages.addUserMessage(caller, other, "Hello there").orElseThrow();

        assertThat(messages.find(caller, other, added.messageId())).isEmpty();
        assertThat(messages.list(caller, conversation)).containsExactly(added);
        assertThat(messages.list(caller, other)).containsExactly(elsewhere);
    }

    /** Keys and end users are per application, so another application of the same tenant reaches nothing. */
    @Test
    void anotherApplicationOfTheTenantCanNeitherAddNorRead() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        Message added = messages.addUserMessage(caller, conversation, "Hello").orElseThrow();
        Owner other = anotherApplicationOf(caller);

        assertThat(messages.addUserMessage(other, conversation, "Me too")).isEmpty();
        assertThat(messages.startAssistantMessage(other, conversation)).isEmpty();
        assertThat(messages.find(other, conversation, added.messageId())).isEmpty();
        assertThat(messages.list(other, conversation)).isEmpty();
        assertThat(rows()).containsExactly(conversation + " user completed Hello");
    }

    /** Row-level security keeps it out even for a caller that names the same application. */
    @Test
    void anotherTenantCanNeitherAddNorRead() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        Message added = messages.addUserMessage(caller, conversation, "Hello").orElseThrow();
        Owner stranger = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        assertThat(messages.addUserMessage(stranger, conversation, "Me too")).isEmpty();
        assertThat(messages.startAssistantMessage(stranger, conversation)).isEmpty();
        assertThat(messages.find(stranger, conversation, added.messageId())).isEmpty();
        assertThat(messages.list(stranger, conversation)).isEmpty();
        assertThat(rows()).containsExactly(conversation + " user completed Hello");
    }

    @Test
    void anUnknownConversationTakesNoMessage() throws SQLException {
        Owner caller = newApplication();
        newConversation(caller);

        for (String unknown : new String[] {"conv_00000000000000000000000000", "not an id", null}) {
            assertThat(messages.addUserMessage(caller, unknown, "Hello")).as(unknown).isEmpty();
            assertThat(messages.startAssistantMessage(caller, unknown)).as(unknown).isEmpty();
            assertThat(messages.list(caller, unknown)).as(unknown).isEmpty();
        }
        assertThat(rows()).isEmpty();
    }

    @Test
    void anUnknownMessageIsNotFound() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        messages.addUserMessage(caller, conversation, "Hello");

        assertThat(messages.find(caller, conversation, "msg_00000000000000000000000000")).isEmpty();
        assertThat(messages.find(caller, conversation, "not an id")).isEmpty();
        assertThat(messages.find(caller, conversation, null)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "\0", "Hello\0there"})
    void aUserMessageNeedsTextTheDatabaseCanStore(String text) throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        assertThatThrownBy(() -> messages.addUserMessage(caller, conversation, text)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isEmpty();
    }

    /** The contract counts characters, as JSON Schema does, so a character outside the BMP counts once. */
    @Test
    void theLimitIs32000Characters() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        for (String text : List.of("a".repeat(32001), ASTRAL.repeat(32001))) {
            assertThatThrownBy(() -> messages.addUserMessage(caller, conversation, text))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("A user message must have 1 to 32000 characters");
        }
        assertThat(rows()).isEmpty();

        for (String text : List.of("a", "a".repeat(32000), ASTRAL.repeat(32000))) {
            assertThat(messages.addUserMessage(caller, conversation, text)).hasValueSatisfying(
                    added -> assertThat(added.text()).isEqualTo(text));
        }
        assertThat(messages.list(caller, conversation)).hasSize(3);
    }

    /** The model generated from the contract carries CreateMessageRequest.text's limits, so this holds the two together. */
    @Test
    void theLimitIsTheContracts() throws Exception {
        Size size = CreateMessageRequest.class.getMethod("getText").getAnnotation(Size.class);

        assertThat(size.min()).isOne();
        assertThat(size.max()).isEqualTo(Messages.MAX_TEXT);
    }

    /** SQL that goes around DatabaseMessages meets the same rules in the table. */
    @ParameterizedTest
    @ValueSource(strings = {
            "'user', 'completed', '', NULL, NULL, NULL, now()",
            "'user', 'completed', repeat('a', 32001), NULL, NULL, NULL, now()",
            "'user', 'completed', NULL, NULL, NULL, NULL, now()",
            "'user', 'in_progress', 'Hello', NULL, NULL, NULL, NULL",
            "'user', 'completed', 'Hello', NULL, NULL, NULL, NULL",
            "'user', 'completed', 'Hello', 1, NULL, NULL, now()",
            "'user', 'completed', 'Hello', NULL, 1, NULL, now()",
            "'user', 'completed', 'Hello', NULL, NULL, 'model_failed', now()",
            "'assistant', 'completed', '', NULL, NULL, NULL, now()",
            "'assistant', 'failed', '', NULL, NULL, 'model_failed', now()",
            "'assistant', 'blocked', '', NULL, NULL, NULL, now()",
            "'assistant', 'in_progress', 'Hi', NULL, NULL, NULL, NULL",
            "'assistant', 'in_progress', '', NULL, NULL, NULL, now()",
            "'assistant', 'in_progress', '', 1, NULL, NULL, NULL",
            "'assistant', 'in_progress', '', NULL, 1, NULL, NULL",
            "'assistant', 'in_progress', '', NULL, NULL, 'model_failed', NULL",
            "'system', 'completed', 'Hello', NULL, NULL, NULL, now()",
            "'system', 'in_progress', '', NULL, NULL, NULL, NULL"})
    void theTableRefusesARowOutsideItsRules(String values) throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        assertRefused(() -> addRow(caller, conversation, "msg_1", values), "violates");
        assertThat(rows()).isEmpty();
    }

    /** The table counts characters too, not bytes: é takes two bytes in UTF-8. */
    @Test
    void theTableTakesARowWithinItsRules() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        assertThat(addRow(caller, conversation, "msg_1", "'user', 'completed', 'a', NULL, NULL, NULL, now()")).isOne();
        assertThat(addRow(caller, conversation, "msg_2", "'user', 'completed', repeat('é', 32000), NULL, NULL, NULL, now()")).isOne();
        assertThat(addRow(caller, conversation, "msg_3", "'assistant', 'in_progress', '', NULL, NULL, NULL, NULL")).isOne();
        assertThat(rows()).hasSize(3);
    }

    /** A foreign key ignores row-level security, so only the tenant in the key keeps a row from another tenant's parent. */
    @Test
    void aMessageCannotHangUnderAnotherTenantsConversation() throws SQLException {
        Owner caller = newApplication();
        newConversation(caller);
        String strangers = newConversation(newApplication());

        assertRefused(() -> addRow(caller, strangers, "msg_1", "'user', 'completed', 'Hello', NULL, NULL, NULL, now()"),
                "violates foreign key constraint");
        assertThat(rows()).isEmpty();
    }

    /** M2 deletes nothing, and an answer will change only through its events. */
    @Test
    void theEngineCanNeitherChangeNorDeleteAMessage() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        messages.addUserMessage(caller, conversation, "Hello");
        messages.startAssistantMessage(caller, conversation);

        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("UPDATE conversation_messages SET text = 'Hi'")),
                "permission denied");
        assertRefused(() -> database.inTenantTx(caller.tenantId(), tx -> tx.update("DELETE FROM conversation_messages")),
                "permission denied");
        assertThat(rows()).containsExactly(conversation + " user completed Hello", conversation + " assistant in_progress ");
    }

    @Test
    void eachMessageHasAnIdOfItsOwn() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        List<String> ids = List.of(
                messages.addUserMessage(caller, conversation, "Hello").orElseThrow().messageId(),
                messages.addUserMessage(caller, conversation, "Hello").orElseThrow().messageId(),
                messages.startAssistantMessage(caller, conversation).orElseThrow().messageId(),
                messages.startAssistantMessage(caller, conversation).orElseThrow().messageId());

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(messages.find(caller, conversation, id)).isPresent());
    }
}
