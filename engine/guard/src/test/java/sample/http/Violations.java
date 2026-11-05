package sample.http;

import aie.guard.Violates;

@Violates("A-1")
class HttpUsesAdapter {

    sample.adapters.fake.FakeModel field;
}

@Violates("A-1")
class HttpUsesDatabasePort {

    sample.ports.DatabasePort field;
}

@Violates({"A-1", "A-2-ports"})
class HttpUsesModelPort {

    sample.ports.ModelPort field;
}

@Violates("A-4-public")
class HttpReachesModuleInternals {

    sample.modules.conversation.internal.ConversationImpl field;
}

@Violates("A-4-public")
class HttpReachesCoreInternals {

    sample.core.tenancy.internal.TenancyImpl field;
}

@Violates("A-13")
class HttpUsesJdbcClient {

    org.springframework.jdbc.core.simple.JdbcClient field;
}

@Violates("A-17")
class HttpUsesApacheClient {

    org.apache.hc.client5.http.impl.classic.CloseableHttpClient field;
}

@Violates("A-18-engine")
class HttpUsesSdk {

    sample.sdk.EngineClient field;
}

@Violates("A-1")
class HttpUsesApp {

    sample.app.Main field;
}
