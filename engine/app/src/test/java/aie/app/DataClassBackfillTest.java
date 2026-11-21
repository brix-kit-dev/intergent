package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M3-3-1: the messages stored before data classes take public, the lowest, and so do the watermarks of their
 * conversations (V8). The conversation module is migrated up to V7 first, as migrate does it, with a question and an
 * answer in progress; then migrate runs the whole way. From then on a message is stored only with a class of its own,
 * while a new conversation starts at public.
 */
@Testcontainers
class DataClassBackfillTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @Test
    void theMessagesFromBeforeTheScriptArePublic() throws Exception {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(DATABASE.getJdbcUrl());
        owner.setUser("engine_owner");
        owner.setPassword(TestDatabase.OWNER_PASSWORD);
        owner.setOptions("-c row_security=off");
        Flyway.configure().dataSource(owner).schemas("engine").createSchemas(false).table("flyway_history_conversation")
                .locations("classpath:db/migration/conversation").baselineOnMigrate(true).baselineVersion("0")
                .target("7").load().migrate();
        TestDatabase.asAdmin(DATABASE, """
                SET search_path = engine;
                INSERT INTO conversation_conversations (conversation_id, tenant_id, application_id, end_user_id)
                VALUES ('conv_1', 'ten_1', 'app_1', 'eu_1');
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, text, completed_at)
                VALUES ('msg_asked', 'ten_1', 'conv_1', 'user', 'completed', 'Hi', now());
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status)
                VALUES ('msg_going', 'ten_1', 'conv_1', 'assistant', 'in_progress');
                """);

        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();

        assertThat(TestDatabase.firstColumn(DATABASE,
                        "SELECT message_id || ' ' || data_class FROM engine.conversation_messages ORDER BY ordinal"))
                .containsExactly("msg_asked public", "msg_going public");
        assertThat(TestDatabase.firstColumn(DATABASE,
                        "SELECT conversation_id || ' ' || data_class_watermark FROM engine.conversation_conversations"))
                .containsExactly("conv_1 public");
        assertThat(TestDatabase.firstColumn(DATABASE, """
                SELECT table_name || '.' || column_name || ' ' || coalesce(column_default, 'no default')
                FROM information_schema.columns
                WHERE table_schema = 'engine' AND table_name LIKE 'conversation\\_%'
                    AND column_name IN ('data_class', 'data_class_watermark')
                ORDER BY table_name
                """)).containsExactly("conversation_conversations.data_class_watermark 'public'::text",
                "conversation_messages.data_class no default");
    }
}
