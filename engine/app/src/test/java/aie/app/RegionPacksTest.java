package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Route;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** M3-1: the region packs, read from the engine's jar and checked before the api role starts (blueprint 12.2, R-4). */
class RegionPacksTest {

    /** The repository's packs, which the build copies into the jar. */
    private static final Path REGIONS = Path.of("../../regions");
    private static final String MODELS = """
            models:
              - id: fake
                adapter: fake
                family: fake
                processing_region: jp
                retention: none
                training: no
                max_data_class: sensitive
                capabilities: [text]
                price: { input: 0, output: 0 }
            """;
    private static final String ROUTES = """
            routes:
              customer_chat:
                models: [fake]
                timeout_seconds: 120
                checks: sentence
            """;

    @Test
    void theJpPackHasTheFakeModelAndRoutesCustomerChatToIt() {
        RegionPack jp = RegionPacks.read("jp");

        assertThat(jp.region()).isEqualTo("jp");
        assertThat(jp.models()).isEqualTo(Map.of("fake", new ModelSpec("fake", "fake", "fake", "jp", 0, false,
                DataClass.SENSITIVE, Set.of(ModelSpec.Capability.TEXT), new ModelSpec.Price(BigDecimal.ZERO, BigDecimal.ZERO))));
        assertThat(jp.routes()).isEqualTo(Map.of("customer_chat",
                new Route(List.of("fake"), Duration.ofSeconds(120), CheckMode.SENTENCE)));
    }

    /** R-4 in CI: every pack of the repository passes the checks the api role runs, and the jar carries it unchanged. */
    @Test
    void everyRegionPackOfTheRepositoryPassesItsChecks() throws IOException {
        List<Path> packs;
        try (Stream<Path> children = Files.list(REGIONS)) {
            packs = children.filter(Files::isDirectory).toList();
        }

        assertThat(packs).extracting(pack -> pack.getFileName().toString()).contains("jp");
        for (Path pack : packs) {
            String region = pack.getFileName().toString();
            RegionPack fromFiles = RegionPacks.parse(region, Files.readString(pack.resolve(RegionPacks.MODELS)),
                    Files.readString(pack.resolve(RegionPacks.ROUTES)));
            assertThat(RegionPacks.read(region)).as(region).isEqualTo(fromFiles);
        }
    }

    @Test
    void theRightFilesMakeAPack() {
        assertThat(RegionPacks.parse("test", MODELS, ROUTES).routes()).containsOnlyKeys("customer_chat");
    }

    /** Each mistake is refused with the file and the model or the task, so the operator knows what to fix. */
    static Stream<Arguments> mistakes() {
        String models = "regions/test/models.yaml";
        String routes = "regions/test/routes.yaml";
        return Stream.of(
                arguments(MODELS, ROUTES.replace("[fake]", "[gpt]"),
                        routes + ": the route of customer_chat names the model gpt, which models.yaml does not have"),
                arguments(MODELS, ROUTES.replace("    checks: sentence\n", ""),
                        routes + ": the route of customer_chat needs the field checks"),
                arguments(MODELS, ROUTES.replace("checks: sentence", "checks: words"),
                        routes + ": the route of customer_chat: checks must be sentence, full or input, not words"),
                arguments(MODELS, ROUTES.replace("customer_chat:", "document_translate:"),
                        routes + " has no route for customer_chat, which the engine asks for"),
                arguments(MODELS, ROUTES.replace("timeout_seconds: 120", "timeout_seconds: 0"),
                        routes + ": the route of customer_chat: timeout_seconds must be a whole number above zero, not 0"),
                arguments(MODELS, ROUTES.replace("timeout_seconds: 120", "timeout_seconds: 1.5"),
                        routes + ": the route of customer_chat: timeout_seconds must be a whole number above zero, not 1.5"),
                arguments(MODELS, ROUTES.replace("customer_chat:", "Customer-Chat:"),
                        routes + ": a task is lower-case letters, digits and _, not Customer-Chat"),
                arguments(MODELS, "routes: {}\n", routes + " has no route"),
                arguments(MODELS, ROUTES + "extra: 1\n", routes + " has the unknown field extra"),
                arguments(MODELS.replace("    retention: none", "    retension: none"), ROUTES,
                        models + ": the model fake has the unknown field retension"),
                arguments(MODELS.replace("    price: { input: 0, output: 0 }\n", ""), ROUTES,
                        models + ": the model fake needs the field price"),
                arguments(MODELS.replace("retention: none", "retention: 30d"), ROUTES,
                        models + ": The model fake takes sensitive data, so its contract must keep nothing (blueprint 7.3)"),
                arguments(MODELS.replace("max_data_class: sensitive", "max_data_class: personal").replace("training: no", "training: yes"),
                        ROUTES, models + ": The model fake takes personal data, so its provider may not train on it (blueprint 7.3)"),
                arguments(MODELS.replace("adapter: fake", "adapter: anthropic"), ROUTES,
                        models + ": the model fake needs the adapter anthropic, which this engine does not have"),
                arguments(MODELS + MODELS.replace("models:\n", ""), ROUTES, models + " has the model fake twice"),
                arguments(MODELS.replace("capabilities: [text]", "capabilities: [text, telepathy]"), ROUTES,
                        models + ": the model fake has the unknown capability telepathy"),
                arguments(MODELS.replace("max_data_class: sensitive", "max_data_class: secret"), ROUTES,
                        models + ": the model fake: max_data_class must be public, internal, personal or sensitive"),
                arguments(MODELS.replace("retention: none", "retention: forever"), ROUTES,
                        models + ": the model fake: retention must be none or a number of days such as 30d, not forever"),
                arguments(MODELS.replace("training: no", "training: maybe"), ROUTES,
                        models + ": the model fake: training must be yes or no, not maybe"),
                arguments(MODELS.replace("input: 0", "input: -1"), ROUTES,
                        models + ": A price is a cost per million tokens in and out, zero or more, not -1 and 0"),
                arguments(MODELS.replace("input: 0", "input: free"), ROUTES,
                        models + ": the model fake: price input must be a number, not free"),
                arguments(MODELS.replace("id: fake", "id: Fake Model"), ROUTES, models + ": A model's id is a deployment and a model"
                        + " such as bedrock-jp/sonnet, in lower-case letters, digits and . _ -, not Fake Model"),
                arguments("models: []\n", ROUTES, models + ": models must be a list of at least one item"),
                arguments(MODELS + "models: []\n", ROUTES, models + " is not valid YAML: "));
    }

    @ParameterizedTest
    @MethodSource("mistakes")
    void aPackWithAMistakeIsRefusedByFileAndName(String models, String routes, String reason) {
        assertThatThrownBy(() -> RegionPacks.parse("test", models, routes))
                .isInstanceOf(IllegalArgumentException.class)
                .message().startsWith(reason);
    }

    /** DEPLOY_REGION names a directory of the jar's regions and nothing else. */
    @Test
    void aNameThatIsNoRegionOrHasNoPackIsRefused() {
        assertThatThrownBy(() -> RegionPacks.read("cn")).hasMessage("There is no region pack cn: the engine has no regions/cn/models.yaml");
        assertThatThrownBy(() -> RegionPacks.read("../jp"))
                .hasMessage("DEPLOY_REGION must be lower-case letters, digits and -, such as jp, not ../jp");
        assertThatThrownBy(() -> RegionPacks.read(null))
                .hasMessage("DEPLOY_REGION must be lower-case letters, digits and -, such as jp, not null");
    }
}
