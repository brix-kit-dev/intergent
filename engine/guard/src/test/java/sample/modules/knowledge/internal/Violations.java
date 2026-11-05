package sample.modules.knowledge.internal;

import aie.guard.Violates;

@Violates("A-2-ports")
class KnowledgeUsesEmbeddingPort {

    sample.ports.EmbeddingPort field;
}

@Violates("A-2-ports")
class KnowledgeUsesOcrPort {

    sample.ports.OcrPort field;
}

@Violates("A-2-ports")
class KnowledgeUsesSpeechPort {

    sample.ports.SpeechPort field;
}

@Violates("A-18-engine")
class KnowledgeUsesSdk {

    sample.sdk.EngineClient field;
}

@Violates("A-18-engine")
class KnowledgeUsesProduct {

    sample.products.support.SupportFlow field;
}

@Violates("T-5")
class SystemTxByMethodReference {

    sample.ports.DatabasePort database;
    java.util.function.Function<java.util.function.Function<sample.ports.Tx, Object>, Object> field = database::inSystemTx;
}

@Violates("A-13")
class KnowledgeUsesPostgresDriver {

    org.postgresql.PGConnection field;
}

@Violates("A-13")
class KnowledgeUsesKingbaseDriver {

    com.kingbase8.Driver field;
}

@Violates("A-17")
class KnowledgeUsesHttpUrlConnection {

    java.net.HttpURLConnection field;
}

@Violates("A-17")
class KnowledgeUsesUrlConnection {

    java.net.URLConnection field;
}
