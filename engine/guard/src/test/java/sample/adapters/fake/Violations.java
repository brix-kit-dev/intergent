package sample.adapters.fake;

import aie.guard.Violates;

@Violates("A-1")
class AdapterUsesCore {

    sample.core.gateway.api.GatewayApi field;
}

@Violates("A-1")
class AdapterUsesModule {

    sample.modules.conversation.api.ConversationApi field;
}

@Violates("A-18-engine")
class AdapterUsesSdk {

    sample.sdk.EngineClient field;
}

@Violates("A-1")
class AdapterUsesHttp {

    sample.http.SessionsController field;
}

@Violates("A-1")
class AdapterUsesApp {

    sample.app.Main field;
}
