package sample.modules.knowledge.api;

import aie.guard.Violates;

@Violates("A-4-tx")
class KnowledgeApiTakesTx {

    void save(sample.ports.Tx tx) {
    }
}

@Violates("A-4-tx")
class KnowledgeApiReturnsTx {

    sample.ports.Tx open() {
        return null;
    }
}

@Violates("A-4-tx")
class KnowledgeApiUsesTxPart {

    sample.ports.Tx.Savepoint field;
}
