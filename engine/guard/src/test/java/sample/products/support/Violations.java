package sample.products.support;

import aie.guard.Violates;

@Violates("A-18-outside")
class ProductUsesCore {

    sample.core.gateway.api.GatewayApi field;
}

@Violates("A-18-outside")
class ProductUsesModule {

    sample.modules.conversation.api.ConversationApi field;
}

@Violates({"A-4-public", "A-18-outside"})
class ProductReachesInternals {

    sample.modules.conversation.internal.ConversationImpl field;
}

@Violates({"A-2-ports", "A-18-outside"})
class ProductUsesModelPort {

    sample.ports.ModelPort field;
}

@Violates("A-2-sdks")
class ProductUsesModelSdk {

    com.openai.client.OpenAIClient field;
}

@Violates("A-18-outside")
class ProductUsesHttp {

    sample.http.SessionsController field;
}

@Violates("A-18-outside")
class ProductUsesAdapter {

    sample.adapters.fake.FakeModel field;
}

@Violates("A-18-outside")
class ProductUsesApp {

    sample.app.Main field;
}
