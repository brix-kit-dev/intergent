package sample.ports;

import aie.guard.Violates;

@Violates("A-1")
class PortUsesCore {

    sample.core.gateway.api.GatewayApi field;
}

@Violates("A-18-engine")
class PortUsesSdk {

    sample.sdk.EngineClient field;
}
