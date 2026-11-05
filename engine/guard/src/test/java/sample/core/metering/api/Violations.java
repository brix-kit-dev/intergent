package sample.core.metering.api;

import aie.guard.Violates;

@Violates("A-4-tx")
class MeteringApiTakesTx {

    void record(sample.ports.Tx tx) {
    }
}
