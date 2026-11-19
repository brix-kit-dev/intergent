package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.modelfake.FakeModel;
import aie.core.gateway.api.Gateway.Completed;
import aie.core.gateway.api.Gateway.Request;
import aie.core.gateway.api.Gateway.Result;
import aie.core.gateway.api.Gateway.Role;
import aie.core.gateway.api.Gateway.Turn;
import aie.core.gateway.api.Gateway.Usage;
import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Route;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.Answers;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelsTest {

    /** The conversation module names its task, so every region pack has to route that very name. */
    @Test
    void theEngineAsksForTheTaskTheConversationModuleNames() {
        assertThat(Models.TASKS).containsExactly(Answers.TASK);
        assertThat(Models.ADAPTERS).containsOnlyKeys("fake");
        assertThat(Models.ADAPTERS.get("fake").get()).isInstanceOf(FakeModel.class);
    }

    @Test
    void theGatewayOfTheJpPackAnswersCustomerChatWithTheFakeModel() {
        List<String> pieces = new ArrayList<>();

        Result result = Models.gateway(RegionPacks.read("jp"), () -> false).answer(
                new Request(Answers.TASK, List.of(new Turn(Role.USER, "Hello")), () -> false), pieces::add);

        assertThat(pieces).hasSize(8).startsWith("Piece 1 of 8. ").endsWith("Piece 8 of 8. ");
        assertThat(result).isEqualTo(new Completed(new Usage(5, 8 * "Piece 1 of 8. ".length())));
    }

    /** RegionPacks refuses such a pack too; this is the gateway's own guard for a pack made any other way. */
    @Test
    void aModelWhoseAdapterTheEngineDoesNotHaveIsRefused() {
        ModelSpec claude = new ModelSpec("bedrock-jp/claude-sonnet-5-5", "anthropic", "claude", "jp", 0, false,
                DataClass.PERSONAL, Set.of(ModelSpec.Capability.TEXT), new ModelSpec.Price(BigDecimal.ONE, BigDecimal.TEN));
        RegionPack pack = new RegionPack("jp", Map.of(claude.id(), claude),
                Map.of(Answers.TASK, new Route(List.of(claude.id()), Duration.ofMinutes(2), CheckMode.SENTENCE)));

        assertThatThrownBy(() -> Models.gateway(pack, () -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The model bedrock-jp/claude-sonnet-5-5 needs the adapter anthropic, which this engine does not have");
    }
}
