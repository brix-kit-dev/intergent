package sample.core.gateway.internal;

import sample.core.gateway.api.GatewayApi;
import sample.core.trust.api.TrustApi;
import sample.ports.EmbeddingPort;
import sample.ports.ModelPort;
import sample.ports.OcrPort;
import sample.ports.RerankPort;
import sample.ports.SpeechPort;

public class GatewayImpl implements GatewayApi {

    TrustApi trust;
    ModelPort model;
    EmbeddingPort embedding;
    RerankPort rerank;
    SpeechPort speech;
    OcrPort ocr;

    ModelPort.Reply call(String prompt) {
        return model.call(new ModelPort.Request(prompt));
    }
}
