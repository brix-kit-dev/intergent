package sample.core.gateway.internal;

import aie.guard.Violates;

@Violates("A-1")
class CoreUsesModule {

    sample.modules.conversation.api.ConversationApi field;
}

@Violates("A-1")
class CoreUsesHttp {

    sample.http.SessionsController field;
}

@Violates("A-1")
class CoreUsesAdapter {

    sample.adapters.fake.FakeModel field;
}

@Violates("A-1")
class CoreUsesApp {

    sample.app.Main field;
}

@Violates("A-4-public")
class GatewayReachesMeteringInternals {

    sample.core.metering.internal.MeteringImpl field;
}

@Violates("T-5")
class GatewayOpensSystemTx {

    sample.ports.DatabasePort database;

    int open() {
        return database.inSystemTx(tx -> 1);
    }
}

@Violates("A-13")
class GatewayUsesConnection {

    java.sql.Connection field;
}

@Violates("A-18-engine")
class CoreUsesProduct {

    sample.products.support.SupportFlow field;
}
