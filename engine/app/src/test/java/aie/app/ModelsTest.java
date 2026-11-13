package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.modelfake.FakeModel;
import aie.core.gateway.api.Gateway.Completed;
import aie.core.gateway.api.Gateway.Request;
import aie.core.gateway.api.Gateway.Result;
import aie.core.gateway.api.Gateway.Role;
import aie.core.gateway.api.Gateway.Turn;
import aie.core.gateway.api.Gateway.Usage;
import aie.core.gateway.internal.Route;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelsTest {

    @Test
    void customerChatGoesToTheFakeModelWithTwoMinutesForAnAnswer() {
        assertThat(Models.registry()).containsOnlyKeys("fake");
        assertThat(Models.registry().get("fake")).isInstanceOf(FakeModel.class);
        assertThat(Models.routes()).isEqualTo(Map.of("customer_chat", new Route(List.of("fake"), Duration.ofMinutes(2))));
    }

    @Test
    void theGatewayAnswersCustomerChatWithTheFakeModel() {
        List<String> pieces = new ArrayList<>();

        Result result = Models.gateway(() -> false).answer(
                new Request(Models.CUSTOMER_CHAT, List.of(new Turn(Role.USER, "Hello")), () -> false), pieces::add);

        assertThat(pieces).hasSize(8).startsWith("Piece 1 of 8. ").endsWith("Piece 8 of 8. ");
        assertThat(result).isEqualTo(new Completed(new Usage(5, 8 * "Piece 1 of 8. ".length())));
    }
}
