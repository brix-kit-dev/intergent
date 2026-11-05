package sample.core.trust.internal;

import aie.guard.Violates;

@Violates("A-2-ports")
class TrustUsesModelPort {

    sample.ports.ModelPort field;
}

@Violates("A-15")
class TrustUsesGateway {

    sample.core.gateway.api.GatewayApi field;
}
