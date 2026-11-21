package aie.app;

import aie.adapters.modelfake.FakeModel;
import aie.adapters.moderationrules.RuleModeration;
import aie.adapters.redactionrules.RuleRedaction;
import aie.core.gateway.api.Gateway;
import aie.core.gateway.internal.RoutingGateway;
import aie.core.metering.api.Metering;
import aie.core.trust.api.Audit;
import aie.core.trust.api.Checks;
import aie.core.trust.internal.TrustChecks;
import aie.modules.conversation.api.Answers;
import aie.ports.ModelPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The adapters this engine has, the tasks it asks the gateway for, and the gateway built from a region pack (blueprint
 * 8.3 and 8.4) with the trust layer's checks (blueprint 9.1). Which models a region uses and how each task is routed is
 * the region pack's, not this class's (M3-1).
 */
final class Models {

    /** The adapters by the name a region pack's models give, each making a new one. Real adapters come in M3-9 to 11. */
    static final Map<String, Supplier<ModelPort>> ADAPTERS = Map.of("fake", FakeModel::new);
    /** The tasks the engine asks the gateway for, so a region pack must route each of them. */
    static final Set<String> TASKS = Set.of(Answers.TASK);

    private Models() {
    }

    /**
     * The trust layer's checks: moderation (M3-4-1) and masking (M3-4-2) by the rules adapters, which look at texts
     * inside the engine. A moderation or masking service would take an adapter's place here.
     */
    static Checks checks() {
        return new TrustChecks(new RuleModeration(), new RuleRedaction());
    }

    /**
     * The gateway of a region pack: each of its models called through a new adapter of the model's kind, with the highest
     * class of data its contract lets it take, and its routes, behind the trust layer's checks and with its audit.
     *
     * @param audit the trust layer's audit, which the gateway adds its entries to (M3-5-1): the role's database
     * @param metering where the gateway records what each call used and cost (M3-6-1): the role's database
     * @param insideTx whether the calling thread is inside a database transaction, so that the gateway can refuse such a
     *     call (A-14): {@code insideTx} of the role's pool
     * @throws IllegalArgumentException for a model whose adapter this engine does not have
     */
    static Gateway gateway(RegionPack pack, Audit audit, Metering metering, BooleanSupplier insideTx) {
        Map<String, RoutingGateway.Model> registry = new LinkedHashMap<>();
        pack.models().forEach((id, spec) -> {
            Supplier<ModelPort> adapter = ADAPTERS.get(spec.adapter());
            if (adapter == null) {
                throw new IllegalArgumentException("The model " + id + " needs the adapter " + spec.adapter()
                        + ", which this engine does not have");
            }
            registry.put(id, new RoutingGateway.Model(adapter.get(), spec.maxDataClass(), spec.price()));
        });
        return new RoutingGateway(registry, pack.routes(), checks(), audit, metering, insideTx);
    }
}
