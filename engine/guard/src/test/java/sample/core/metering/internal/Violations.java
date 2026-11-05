package sample.core.metering.internal;

import aie.guard.Violates;

@Violates("A-2-ports")
class MeteringUsesRerankPort {

    sample.ports.RerankPort field;
}

@Violates("T-5")
class MeteringOpensSystemTx {

    sample.ports.DatabasePort database;

    int open() {
        return database.inSystemTx(tx -> 1);
    }
}

@Violates("A-17")
class MeteringUsesRestClient {

    org.springframework.web.client.RestClient field;
}
