package aie.app;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.core.gateway.api.Gateway;
import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.IdempotencyKeys;
import aie.core.tenancy.api.LowerTenants;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SiteKeyLookup;
import aie.core.tenancy.internal.DatabaseApiKeyLookup;
import aie.core.tenancy.internal.DatabaseApiKeyManagement;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseIdempotencyKeys;
import aie.core.tenancy.internal.DatabaseLowerTenants;
import aie.core.tenancy.internal.DatabaseSiteKeyLookup;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.internal.DatabaseConversations;
import aie.modules.conversation.internal.DatabaseMessages;
import aie.modules.conversation.internal.GeneratingAnswers;
import aie.modules.conversation.internal.LocalEventSignals;
import aie.ports.DatabaseException;
import java.time.Clock;
import java.util.function.Consumer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** The api role: serves requests from the interface layer. */
@SpringBootApplication(scanBasePackages = "aie.http")
class ApiRole {

    /** Requests run on virtual threads, so the pool, not the thread count, caps the database connections. */
    static final int POOL_SIZE = 10;

    static int run(String... args) {
        try {
            start(args);
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Startup refused: " + e.getMessage());
            return EngineApplication.EXIT_FAILED;
        }
    }

    /**
     * The session keys are checked before the pool opens, so a missing or weak key never gets as far as the database.
     * The pool runs the self-check of blueprint 7.6 before its first connection, so a role that may not serve traffic
     * never opens a port. The gateway comes next, because it asks the pool whether a call comes from inside a
     * transaction (A-14); a route to a model that does not exist still refuses startup. The context closes the pool as
     * it closes, because the pool is AutoCloseable, and so is the generator of answers. Beans close in the reverse order
     * they were registered in, so the generator lets its answers finish or end them while the pool is still open. A context that fails before it has made its beans would not, and
     * neither would a gateway that refuses its routes, so the role closes the pool itself then.
     */
    static ConfigurableApplicationContext start(String... args) {
        RoleSettings settings = RoleSettings.read(args);
        String address = settings.required(RoleSettings.DATABASE_URL);
        String password = settings.required(RoleSettings.APP_PASSWORD);
        SessionTokens sessionTokens = SessionKeys.tokens(settings, Clock.systemUTC());
        PgPool database = PgRuntime.pool(address, password, POOL_SIZE);
        try {
            Gateway gateway = Models.gateway(database::insideTx);
            return serve(context -> {
                context.registerBean(PgPool.class, () -> database);
                context.registerBean(ApiKeyLookup.class, () -> new DatabaseApiKeyLookup(database));
                context.registerBean(ApiKeyManagement.class, () -> new DatabaseApiKeyManagement(database));
                context.registerBean(IdempotencyKeys.class, () -> new DatabaseIdempotencyKeys(database));
                context.registerBean(LowerTenants.class, () -> new DatabaseLowerTenants(database));
                context.registerBean(EndUsers.class, () -> new DatabaseEndUsers(database));
                context.registerBean(SiteKeyLookup.class, () -> new DatabaseSiteKeyLookup(database));
                context.registerBean(SessionTokens.class, () -> sessionTokens);
                context.registerBean(Gateway.class, () -> gateway);
                context.registerBean(Conversations.class, () -> new DatabaseConversations(database));
                EventSignals signals = new LocalEventSignals();
                Messages messages = new DatabaseMessages(database, signals);
                context.registerBean(EventSignals.class, () -> signals);
                context.registerBean(Messages.class, () -> messages);
                context.registerBean(Answers.class, () -> new GeneratingAnswers(messages, gateway));
            }, args);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    /** The web layer, with the beans that connect it to the rest of the engine. Tests pass stand-ins for them. */
    static ConfigurableApplicationContext serve(Consumer<GenericApplicationContext> beans, String... args) {
        return new SpringApplicationBuilder(ApiRole.class)
                .web(WebApplicationType.SERVLET)
                .initializers(context -> beans.accept((GenericApplicationContext) context))
                .run(args);
    }
}
