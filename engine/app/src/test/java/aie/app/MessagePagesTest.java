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
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Message;
import aie.modules.conversation.api.Messages.Page;
import aie.modules.conversation.api.Messages.Status;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.ports.DatabasePort;
import aie.ports.Row;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Messages.page (M2-13-2), the history a page at a time, on the real scripts of both modules, as engine_app. */
@Testcontainers
class MessagePagesTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
    private static Recording recording;
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
        recording = new Recording(database);
        messages = new DatabaseMessages(recording);
        conversations = new DatabaseConversations(database);
        endUsers = new DatabaseEndUsers(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.conversation_conversations CASCADE");
        recording.statements.clear();
    }

    /** A caller of a new tenant's application; the key ID plays no part here. */
    private static Owner newApplication() {
        String tenant = provisioning.createTenant("jp1", null);
        return new Owner("key_unused", tenant, provisioning.createApplication(tenant, "test", "internal"));
    }

    private static String newConversation(Owner caller) {
        return conversations.start(caller, endUsers.resolve(caller, "user-1"), null).conversationId();
    }

    /** A conversation of this many messages: questions, each followed by an answer that is still being written. */
    private static List<Message> conversationOf(Owner caller, String conversation, int count) {
        List<Message> added = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            added.add(i % 2 == 1 ? messages.addUserMessage(caller, conversation, "Question " + i).orElseThrow()
                    : messages.startAssistantMessage(caller, conversation).orElseThrow());
        }
        return added;
    }

    private static Page found(List<Message> page, boolean more) {
        return new Page.Found(page, more);
    }

    /** Each page starts after the last message of the one before, and together they are the conversation, oldest first. */
    @Test
    void pagesWalkTheConversationOldestFirst() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        List<Message> all = conversationOf(caller, conversation, 5);

        Page first = messages.page(caller, conversation, null, 2);
        Page second = messages.page(caller, conversation, all.get(1).messageId(), 2);
        Page last = messages.page(caller, conversation, all.get(3).messageId(), 2);

        assertThat(first).isEqualTo(found(all.subList(0, 2), true));
        assertThat(second).isEqualTo(found(all.subList(2, 4), true));
        assertThat(last).isEqualTo(found(all.subList(4, 5), false));
        assertThat(messages.list(caller, conversation)).isEqualTo(all);
    }

    /** A page that ends with the last message says that no more come, though it is full: the read looks one further. */
    @Test
    void aFullLastPageSaysNoMoreCome() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        List<Message> all = conversationOf(caller, conversation, 4);

        assertThat(messages.page(caller, conversation, null, 4)).isEqualTo(found(all, false));
        assertThat(messages.page(caller, conversation, null, 3)).isEqualTo(found(all.subList(0, 3), true));
        assertThat(messages.page(caller, conversation, all.get(1).messageId(), 2)).isEqualTo(found(all.subList(2, 4), false));
    }

    @Test
    void anEmptyConversationIsAnEmptyPage() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        assertThat(messages.page(caller, conversation, null, 20)).isEqualTo(found(List.of(), false));
    }

    @Test
    void afterTheLastMessageThePageIsEmpty() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        List<Message> all = conversationOf(caller, conversation, 3);

        assertThat(messages.page(caller, conversation, all.getLast().messageId(), 20)).isEqualTo(found(List.of(), false));
    }

    /** A position is a message of this conversation: one of another conversation of the same end user is none. */
    @Test
    void aPositionIsAMessageOfThisConversation() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String other = newConversation(caller);
        conversationOf(caller, conversation, 3);
        Message elsewhere = conversationOf(caller, other, 1).getFirst();

        for (String position : new String[] {elsewhere.messageId(), "msg_00000000000000000000000000", "not an id"}) {
            assertThat(messages.page(caller, conversation, position, 20)).as(position).isEqualTo(new Page.NoSuchPosition());
        }
    }

    /**
     * Another application of the tenant, another tenant and an unknown ID reach no conversation, so there is no page of it,
     * whatever the position: the conversation comes first, so a position tells nothing about it.
     */
    @Test
    void aConversationOutOfReachHasNoPage() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String position = conversationOf(caller, conversation, 2).getFirst().messageId();
        Owner other = new Owner("key_unused", caller.tenantId(), provisioning.createApplication(caller.tenantId(), "test", "internal"));
        Owner stranger = new Owner("key_unused", newApplication().tenantId(), caller.applicationId());

        for (Owner outside : List.of(other, stranger)) {
            assertThat(messages.page(outside, conversation, null, 20)).isEqualTo(new Page.NotFound());
            assertThat(messages.page(outside, conversation, position, 20)).isEqualTo(new Page.NotFound());
            assertThat(messages.page(outside, conversation, "msg_00000000000000000000000000", 20)).isEqualTo(new Page.NotFound());
        }
        for (String unknown : new String[] {"conv_00000000000000000000000000", "not an id", null}) {
            assertThat(messages.page(caller, unknown, null, 20)).as(unknown).isEqualTo(new Page.NotFound());
            assertThat(messages.page(caller, unknown, position, 20)).as(unknown).isEqualTo(new Page.NotFound());
        }
    }

    /** An answer being written has its deltas so far as its text on a page, as in find and list. */
    @Test
    void anAnswerInProgressHasItsTextSoFar() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        Messages.Asked asked = messages.ask(caller, conversation, "Hello?").orElseThrow();
        messages.addDelta(caller, conversation, asked.answer().messageId(), "Hel");
        messages.addDelta(caller, conversation, asked.answer().messageId(), "lo");

        Page page = messages.page(caller, conversation, null, 20);

        assertThat(page).isInstanceOfSatisfying(Page.Found.class, found -> {
            assertThat(found.messages()).extracting(Message::text).containsExactly("Hello?", "Hello");
            assertThat(found.messages().getLast().status()).isEqualTo(Status.IN_PROGRESS);
            assertThat(found.messages()).isEqualTo(messages.list(caller, conversation));
        });
    }

    /**
     * The order is the database's own number, on a page as in list: each later row gets a smaller ID and an earlier time,
     * and the first row is moved behind the others where they lie, as in MessagesTest. With index scans off for
     * engine_app, only ORDER BY can keep the order, and only the number can tell where a position is.
     */
    @Test
    void aPageFollowsTheDatabasesOwnNumber() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String add = """
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, text, created_at, completed_at)
                VALUES (?, ?, ?, 'user', 'completed', ?, now() - ?::interval, now())
                """;
        database.inTenantTx(caller.tenantId(), tx -> tx.update(add, "msg_C", caller.tenantId(), conversation, "first", "0 hours"));
        database.inTenantTx(caller.tenantId(), tx -> tx.update(add, "msg_B", caller.tenantId(), conversation, "second", "1 hour"));
        database.inTenantTx(caller.tenantId(), tx -> tx.update(add, "msg_A", caller.tenantId(), conversation, "third", "2 hours"));
        TestDatabase.asAdmin(DATABASE,
                "SET session_replication_role = replica; UPDATE engine.conversation_messages SET text = text WHERE message_id = 'msg_C'");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_messages"))
                .as("where the rows lie").containsExactly("msg_B", "msg_A", "msg_C");

        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app SET enable_indexscan = off");
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app SET enable_bitmapscan = off");
        try {
            assertThat(ids(messages.page(caller, conversation, null, 2))).containsExactly("msg_C", "msg_B", "more");
            assertThat(ids(messages.page(caller, conversation, "msg_C", 5))).containsExactly("msg_B", "msg_A");
            assertThat(ids(messages.page(caller, conversation, "msg_B", 5))).containsExactly("msg_A");
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app RESET enable_indexscan");
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app RESET enable_bitmapscan");
        }
    }

    /** The page's message IDs, then "more" when more come. */
    private static List<String> ids(Page page) {
        assertThat(page).isInstanceOf(Page.Found.class);
        Page.Found found = (Page.Found) page;
        List<String> ids = new ArrayList<>(found.messages().stream().map(Message::messageId).toList());
        if (found.more()) {
            ids.add("more");
        }
        return ids;
    }

    @Test
    void aPageHoldsOneMessageOrMore() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);

        for (int limit : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> messages.page(caller, conversation, null, limit)).as("limit " + limit)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("A page holds 1 message or more");
        }
    }

    /** The largest limit an int holds still reads the whole conversation, and says no more come. */
    @Test
    void theLargestLimitReadsEverything() {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        List<Message> all = conversationOf(caller, conversation, 3);

        assertThat(messages.page(caller, conversation, null, Integer.MAX_VALUE)).isEqualTo(found(all, false));
    }

    /**
     * A page of a long conversation reads its own rows, in order, through the index on the conversation and the number,
     * from its position, and stops at the page's end: the plan the database makes for the very statement the page ran
     * looks the position up in the index, and has no sort and no scan of the whole table. Five thousand rows make any
     * other plan cost far more, so the planner's choice is clear.
     */
    @Test
    void aPageOfALongConversationReadsOnlyItsOwnRowsInOrder() throws SQLException {
        Owner caller = newApplication();
        String conversation = newConversation(caller);
        String other = newConversation(caller);
        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.conversation_messages (message_id, tenant_id, conversation_id, role, status, text, completed_at)
                SELECT 'msg_long' || g, '%1$s', CASE WHEN g %% 2 = 0 THEN '%2$s' ELSE '%3$s' END, 'user', 'completed',
                    'Message ' || g, now()
                FROM generate_series(1, 10000) g;
                ANALYZE engine.conversation_messages
                """.formatted(caller.tenantId(), conversation, other));
        recording.statements.clear();

        Page page = messages.page(caller, conversation, "msg_long5000", 3);

        assertThat(ids(page)).containsExactly("msg_long5002", "msg_long5004", "msg_long5006", "more");
        Statement read = recording.statements.getLast();
        assertThat(read.sql()).contains("ORDER BY m.ordinal");
        JsonNode plan = plan(caller, read);
        List<JsonNode> nodes = new ArrayList<>();
        collect(plan, nodes);
        assertThat(nodes).as(plan.toString()).anySatisfy(node -> {
            assertThat(node.path("Node Type").asString()).startsWith("Index");
            assertThat(node.path("Index Name").asString()).isEqualTo("conversation_messages_conversation_order");
            assertThat(node.path("Index Cond").asString()).contains("conversation_id = ", "ordinal > ");
        });
        assertThat(nodes).as(plan.toString()).noneSatisfy(node ->
                assertThat(node.path("Node Type").asString()).isIn("Sort", "Incremental Sort", "Seq Scan"));
    }

    /** The plan the database makes for a statement, with the same arguments, as engine_app in the caller's tenant. */
    private static JsonNode plan(Owner caller, Statement statement) {
        String json = database.inTenantTx(caller.tenantId(), tx -> tx.query(
                "EXPLAIN (FORMAT JSON) " + statement.sql(), row -> row.string("QUERY PLAN"), statement.arguments().toArray()))
                .getFirst();
        return JsonMapper.builder().build().readTree(json).get(0).get("Plan");
    }

    /** The node and those below it, but not the plans of its subqueries, which run apart from the page's rows. */
    private static void collect(JsonNode node, List<JsonNode> nodes) {
        nodes.add(node);
        for (JsonNode child : node.path("Plans")) {
            String relationship = child.path("Parent Relationship").asString();
            if (!relationship.equals("SubPlan") && !relationship.equals("InitPlan")) {
                collect(child, nodes);
            }
        }
    }

    /** A statement DatabaseMessages ran, with its arguments. */
    private record Statement(String sql, List<Object> arguments) {
    }

    /** Runs everything on the real database, and remembers each statement, so a test can ask for its plan. */
    private static final class Recording implements DatabasePort {

        final List<Statement> statements = new CopyOnWriteArrayList<>();
        private final DatabasePort database;

        Recording(DatabasePort database) {
            this.database = database;
        }

        @Override
        public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
            return database.inTenantTx(tenantId, tx -> work.apply(recorded(tx)));
        }

        @Override
        public <T> T inSystemTx(Function<Tx, T> work) {
            return database.inSystemTx(tx -> work.apply(recorded(tx)));
        }

        @Override
        public boolean insideTx() {
            return database.insideTx();
        }

        private Tx recorded(Tx tx) {
            return new Tx() {

                @Override
                public int update(String sql, Object... arguments) {
                    statements.add(new Statement(sql, Arrays.asList(arguments)));
                    return tx.update(sql, arguments);
                }

                @Override
                public <R> List<R> query(String sql, Function<Row, R> mapper, Object... arguments) {
                    statements.add(new Statement(sql, Arrays.asList(arguments)));
                    return tx.query(sql, mapper, arguments);
                }
            };
        }
    }
}
