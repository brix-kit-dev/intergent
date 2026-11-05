package sample.modules.conversation.internal;

import aie.guard.Violates;

@Violates("A-2-ports")
class ModuleUsesModelPort {

    sample.ports.ModelPort field;
}

@Violates("A-2-ports")
class ModuleUsesModelPortRecord {

    sample.ports.ModelPort.Request field;
}

@Violates("A-2-ports")
class ModuleUsesModelPortInAList {

    java.util.List<sample.ports.ModelPort> field;
}

@Violates("A-2-ports")
class ModuleUsesModelPortArray {

    sample.ports.ModelPort[] field;
}

@Violates("A-2-ports")
class ModuleUsesModelPortInASignature {

    Object call(sample.ports.ModelPort port) {
        return port;
    }
}

@Violates("A-2-ports")
class ModuleCallsModelPortInALambda {

    java.util.function.Function<Object, Object> field = port -> ((sample.ports.ModelPort) port).call(null);
}

@Violates("A-2-sdks")
class ModuleUsesAnthropicSdk {

    com.anthropic.client.AnthropicClient field;
}

@Violates("A-2-sdks")
class ModuleUsesOpenAiSdk {

    com.openai.client.OpenAIClient field;
}

@Violates("A-2-sdks")
class ModuleUsesGoogleSdk {

    com.google.genai.Client field;
}

@Violates("A-2-sdks")
class ModuleUsesVertexSdk {

    com.google.cloud.vertexai.VertexAI field;
}

@Violates("A-2-sdks")
class ModuleUsesBedrockSdk {

    software.amazon.awssdk.services.bedrock.BedrockClient field;
}

@Violates("A-2-sdks")
class ModuleUsesBedrockRuntimeSdk {

    software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient field;
}

@Violates("A-2-sdks")
class ModuleUsesDashScopeSdk {

    com.alibaba.dashscope.aigc.generation.Generation field;
}

@Violates("A-2-sdks")
class ModuleUsesSpringAi {

    org.springframework.ai.chat.model.ChatModel field;
}

@Violates("A-4-public")
class ModuleReachesInternals {

    sample.modules.knowledge.internal.KnowledgeImpl field;
}

@Violates("A-4-public")
class ModuleReachesAnUnnamedPackage {

    sample.modules.knowledge.search.Search field;
}

@Violates("A-4-public")
class ModuleReachesInternalsThroughAnArray {

    sample.modules.knowledge.internal.KnowledgeImpl[] field;
}

@Violates("A-1")
class ModuleUsesHttp {

    sample.http.SessionsController field;
}

@Violates("A-1")
class ModuleUsesAdapter {

    sample.adapters.fake.FakeModel field;
}

@Violates("A-1")
class ModuleUsesApp {

    sample.app.Main field;
}

@Violates("A-13")
class ModuleUsesDataSource {

    javax.sql.DataSource field;
}

@Violates("A-13")
class ModuleUsesJdbcClient {

    org.springframework.jdbc.core.simple.JdbcClient field;
}

@Violates("A-13")
class ModuleUsesSpringTransactional {

    @org.springframework.transaction.annotation.Transactional
    void save() {
    }
}

@Violates("A-13")
class ModuleUsesJakartaTransactional {

    @jakarta.transaction.Transactional
    void save() {
    }
}

@Violates("T-5")
class ModuleOpensSystemTx {

    sample.ports.DatabasePort database;

    int open() {
        return database.inSystemTx(tx -> 1);
    }
}

@Violates("A-17")
class ModuleUsesSocket {

    java.net.Socket field;
}

@Violates("A-17")
class ModuleOpensUrl {

    Object open(java.net.URL url) throws java.io.IOException {
        return url.openConnection();
    }
}

@Violates("A-17")
class ModuleUsesWebClient {

    org.springframework.web.reactive.function.client.WebClient field;
}

@Violates("A-17")
class ModuleUsesClientHttpRequest {

    org.springframework.http.client.ClientHttpRequest field;
}

@Violates("A-17")
class ModuleUsesOkHttp {

    okhttp3.OkHttpClient field;
}
