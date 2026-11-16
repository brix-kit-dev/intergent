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
 * M2-11-1: the answers started before leases existed get one when the scripts add them (V6). The conversation module is
 * migrated up to V5 first, as migrate does it, with an answer in progress and one that ended; then migrate runs the whole
 * way. Only the answer in progress gets a lease, of 30 seconds: its writer renews nothing, so a reader may end it once the
 * lease runs out.
 */
@Testcontainers
class LeaseBackfillTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @Test
    void anAnswerInProgressBeforeTheScriptGetsALease() throws Exception {
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
                .target("5").load().migrate();
        TestDatabase.asAdmin(DATABASE, """
                SET search_path = engine;
                INSERT INTO conversation_conversations (conversation_id, tenant_id, application_id, end_user_id)
                VALUES ('conv_1', 'ten_1', 'app_1', 'eu_1');
                INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status)
                VALUES ('msg_going', 'ten_1', 'conv_1', 'assistant', 'in_progress'),
                       ('msg_done', 'ten_1', 'conv_1', 'assistant', 'in_progress');
                INSERT INTO conversation_events (tenant_id, message_id, type)
                VALUES ('ten_1', 'msg_going', 'message.started'), ('ten_1', 'msg_done', 'message.started');
                INSERT INTO conversation_events (tenant_id, message_id, type, input_tokens, output_tokens)
                VALUES ('ten_1', 'msg_done', 'message.completed', 1, 1);
                """);

        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();

        assertThat(TestDatabase.firstColumn(DATABASE, """
                SELECT message_id || ' ' || (extract(epoch FROM expires_at - now()) BETWEEN 20 AND 30)::text
                FROM engine.conversation_answer_leases
                """)).containsExactly("msg_going true");
        assertThat(TestDatabase.firstColumn(DATABASE, """
                SELECT relname || ' ' || relforcerowsecurity FROM pg_class
                WHERE oid IN ('engine.conversation_messages'::regclass, 'engine.conversation_answer_leases'::regclass)
                ORDER BY relname
                """)).containsExactly("conversation_answer_leases true", "conversation_messages true");
    }
}
