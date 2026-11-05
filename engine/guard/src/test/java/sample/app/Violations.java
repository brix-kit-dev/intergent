package sample.app;

import aie.guard.Violates;

@Violates("A-18-engine")
class AppUsesProduct {

    sample.products.support.SupportFlow field;
}

@Violates("A-18-engine")
class AppUsesSdk {

    sample.sdk.EngineClient field;
}

@Violates("A-17")
class AppSendsHttp {

    java.net.http.HttpClient field;
}

@Violates("A-2-sdks")
class AppUsesModelSdk {

    com.anthropic.client.AnthropicClient field;
}

@Violates("T-5")
class AppOpensSystemTx {

    sample.ports.DatabasePort database;

    int open() {
        return database.inSystemTx(tx -> 1);
    }
}
