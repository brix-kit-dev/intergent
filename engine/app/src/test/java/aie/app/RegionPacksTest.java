package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Prompt;
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

/**
 * M3-1: the region packs, read from the engine's jar and checked before the api role starts (blueprint 12.2, R-4); with
 * their policies since M3-4-4.
 */
class RegionPacksTest {

    /** The repository's packs, which the build copies into the jar. */
    private static final Path REGIONS = Path.of("../../regions");
    /** The repository's prompts, which the build copies into the jar too (M3-2). */
    private static final Path PROMPTS = Path.of("../../prompts");
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
                prompts:
                  fake: { version: customer_chat/fake/v1 }
            """;
    private static final String POLICY = """
            policy:
              tasks: [customer_chat]
              ai_disclosure: required
            """;

    @Test
    void theJpPackHasTheFakeModelAndRoutesCustomerChatToIt() throws IOException {
        RegionPack jp = RegionPacks.read("jp");

        assertThat(jp.region()).isEqualTo("jp");
        assertThat(jp.models()).isEqualTo(Map.of("fake", new ModelSpec("fake", "fake", "fake", "jp", 0, false,
                DataClass.SENSITIVE, Set.of(ModelSpec.Capability.TEXT), new ModelSpec.Price(BigDecimal.ZERO, BigDecimal.ZERO))));
        assertThat(jp.routes()).isEqualTo(Map.of("customer_chat",
                new Route(List.of("fake"), Duration.ofSeconds(120), CheckMode.SENTENCE, Map.of("fake",
                        new Prompt("customer_chat/fake/v1", Files.readString(PROMPTS.resolve("customer_chat/fake/v1.txt")))))));
        assertThat(jp.policy()).isEqualTo(new RegionPack.Policy(Set.of("customer_chat"), RegionPack.AiDisclosure.REQUIRED));
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
                    Files.readString(pack.resolve(RegionPacks.ROUTES)), Files.readString(pack.resolve(RegionPacks.POLICY)));
            assertThat(RegionPacks.read(region)).as(region).isEqualTo(fromFiles);
        }
    }

    @Test
    void theRightFilesMakeAPack() {
        RegionPack pack = RegionPacks.parse("test", MODELS, ROUTES, POLICY);

        assertThat(pack.routes()).containsOnlyKeys("customer_chat");
        assertThat(pack.policy()).isEqualTo(new RegionPack.Policy(Set.of("customer_chat"), RegionPack.AiDisclosure.REQUIRED));
        assertThat(RegionPacks.parse("test", MODELS, ROUTES, POLICY.replace("required", "not_required")).policy().aiDisclosure())
                .isEqualTo(RegionPack.AiDisclosure.NOT_REQUIRED);
    }

    /**
     * M3-4-4: a pack may offer nothing, with no route then; the gate refuses every request for a task there, and the api
     * role still starts.
     */
    @Test
    void aPackMayOfferNothing() {
        RegionPack pack = RegionPacks.parse("test", MODELS, "routes: {}\n", "policy:\n  tasks: []\n  ai_disclosure: not_required\n");

        assertThat(pack.routes()).isEmpty();
        assertThat(pack.policy().tasks()).isEmpty();
    }

    /** Each mistake is refused with the file and the model or the task, so the operator knows what to fix. */
    static Stream<Arguments> mistakes() {
        String models = "regions/test/models.yaml";
        String routes = "regions/test/routes.yaml";
        String policy = "regions/test/policy.yaml";
        return Stream.of(
                arguments(MODELS, ROUTES, POLICY.replace("[customer_chat]", "[]"),
                        routes + " has a route for customer_chat, which policy.yaml does not offer"),
                arguments(MODELS, "routes: {}\n", POLICY, routes + " has no route for customer_chat, which policy.yaml offers"),
                arguments(MODELS, ROUTES, POLICY.replace("[customer_chat]", "[customer_chat, vision_forensics]"),
                        policy + " offers vision_forensics, which the engine does not have"),
                arguments(MODELS, ROUTES, POLICY.replace("[customer_chat]", "[customer_chat, customer_chat]"),
                        policy + " offers customer_chat twice"),
                arguments(MODELS, ROUTES, POLICY.replace("[customer_chat]", "[Customer-Chat]"),
                        policy + ": a task is lower-case letters, digits and _, not Customer-Chat"),
                arguments(MODELS, ROUTES, POLICY.replace("[customer_chat]", "customer_chat"),
                        policy + ": tasks must be a list of the tasks the region offers, not customer_chat"),
                arguments(MODELS, ROUTES, POLICY.replace("[customer_chat]", "[7]"), policy + ": each task must be text, not 7"),
                arguments(MODELS, ROUTES, POLICY.replace("ai_disclosure: required", "ai_disclosure: maybe"),
                        policy + ": ai_disclosure must be required or not_required, not maybe"),
                arguments(MODELS, ROUTES, POLICY.replace("ai_disclosure: required", "ai_disclosure: yes"),
                        policy + ": ai_disclosure must be required or not_required, not true"),
                arguments(MODELS, ROUTES, POLICY.replace("  ai_disclosure: required\n", ""), policy + ": policy needs the field ai_disclosure"),
                arguments(MODELS, ROUTES, POLICY.replace("  tasks: [customer_chat]\n", ""), policy + ": policy needs the field tasks"),
                arguments(MODELS, ROUTES, POLICY + "  ai_content_label: required\n", policy + ": policy has the unknown field ai_content_label"),
                arguments(MODELS, ROUTES, "tasks: [customer_chat]\n", policy + " has the unknown field tasks"),
                arguments(MODELS, ROUTES.replace("[fake]", "[gpt]"), POLICY,
                        routes + ": the route of customer_chat names the model gpt, which models.yaml does not have"),
                arguments(MODELS, ROUTES.replace("    checks: sentence\n", ""), POLICY,
                        routes + ": the route of customer_chat needs the field checks"),
                arguments(MODELS, ROUTES.replace("checks: sentence", "checks: words"), POLICY,
                        routes + ": the route of customer_chat: checks must be sentence, full or input, not words"),
                arguments(MODELS, ROUTES.replace("customer_chat", "other_task"), POLICY,
                        routes + " has a route for other_task, which policy.yaml does not offer"),
                arguments(MODELS, ROUTES.replace("timeout_seconds: 120", "timeout_seconds: 0"), POLICY,
                        routes + ": the route of customer_chat: timeout_seconds must be a whole number above zero, not 0"),
                arguments(MODELS, ROUTES.replace("timeout_seconds: 120", "timeout_seconds: 1.5"), POLICY,
                        routes + ": the route of customer_chat: timeout_seconds must be a whole number above zero, not 1.5"),
                arguments(MODELS, ROUTES.replace("customer_chat:", "Customer-Chat:"), POLICY,
                        routes + ": a task is lower-case letters, digits and _, not Customer-Chat"),
                arguments(MODELS, ROUTES + "extra: 1\n", POLICY, routes + " has the unknown field extra"),
                arguments(MODELS.replace("    retention: none", "    retension: none"), ROUTES, POLICY,
                        models + ": the model fake has the unknown field retension"),
                arguments(MODELS.replace("    price: { input: 0, output: 0 }\n", ""), ROUTES, POLICY,
                        models + ": the model fake needs the field price"),
                arguments(MODELS.replace("retention: none", "retention: 30d"), ROUTES, POLICY,
                        models + ": The model fake takes sensitive data, so its contract must keep nothing (blueprint 7.3)"),
                arguments(MODELS.replace("max_data_class: sensitive", "max_data_class: personal").replace("training: no", "training: yes"),
                        ROUTES, POLICY, models + ": The model fake takes personal data, so its provider may not train on it (blueprint 7.3)"),
                arguments(MODELS.replace("adapter: fake", "adapter: anthropic"), ROUTES, POLICY,
                        models + ": the model fake needs the adapter anthropic, which this engine does not have"),
                arguments(MODELS + MODELS.replace("models:\n", ""), ROUTES, POLICY, models + " has the model fake twice"),
                arguments(MODELS.replace("capabilities: [text]", "capabilities: [text, telepathy]"), ROUTES, POLICY,
                        models + ": the model fake has the unknown capability telepathy"),
                arguments(MODELS.replace("max_data_class: sensitive", "max_data_class: secret"), ROUTES, POLICY,
                        models + ": the model fake: max_data_class must be public, internal, personal or sensitive"),
                arguments(MODELS.replace("retention: none", "retention: forever"), ROUTES, POLICY,
                        models + ": the model fake: retention must be none or a number of days such as 30d, not forever"),
                arguments(MODELS.replace("training: no", "training: maybe"), ROUTES, POLICY,
                        models + ": the model fake: training must be yes or no, not maybe"),
                arguments(MODELS.replace("input: 0", "input: -1"), ROUTES, POLICY,
                        models + ": A price is a cost per million tokens in and out, zero or more, not -1 and 0"),
                arguments(MODELS.replace("input: 0", "input: free"), ROUTES, POLICY,
                        models + ": the model fake: price input must be a number, not free"),
                arguments(MODELS.replace("id: fake", "id: Fake Model"), ROUTES, POLICY, models + ": A model's id is a deployment and a model"
                        + " such as bedrock-jp/sonnet, in lower-case letters, digits and . _ -, not Fake Model"),
                arguments("models: []\n", ROUTES, POLICY, models + ": models must be a list of at least one item"),
                arguments(MODELS, ROUTES.replace("    prompts:\n      fake: { version: customer_chat/fake/v1 }\n", ""), POLICY,
                        routes + ": the route of customer_chat needs the field prompts"),
                arguments(MODELS, ROUTES.replace("fake: { version", "claude: { version"), POLICY,
                        routes + ": the route of customer_chat: prompts names the family claude, which none of its models is"),
                arguments(MODELS, ROUTES.replace("      fake: { version: customer_chat/fake/v1 }\n", "      {}\n").replace("prompts:\n      {}", "prompts: {}"), POLICY,
                        routes + ": the route of customer_chat needs a prompt for the family fake"),
                arguments(MODELS, ROUTES.replace("version: customer_chat/fake/v1", "version: document_translate/fake/v1"), POLICY, routes
                        + ": the route of customer_chat: the prompt of fake: version must be customer_chat/fake/v and a number, not document_translate/fake/v1"),
                arguments(MODELS, ROUTES.replace("version: customer_chat/fake/v1", "version: customer_chat/fake/v9"), POLICY,
                        routes + ": the route of customer_chat: the prompt of fake: the engine has no prompts/customer_chat/fake/v9.txt"),
                arguments(MODELS, ROUTES.replace("{ version: customer_chat/fake/v1 }", "{ version: customer_chat/fake/v1, canary: customer_chat/fake/v2 }"), POLICY,
                        routes + ": the route of customer_chat: the prompt of fake has the unknown field canary"),
                arguments(MODELS, ROUTES.replace("{ version: customer_chat/fake/v1 }", "customer_chat/fake/v1"), POLICY,
                        routes + ": the route of customer_chat: the prompt of fake must be a map of names to values"),
                arguments(MODELS + "models: []\n", ROUTES, POLICY, models + " is not valid YAML: "));
    }

    @ParameterizedTest
    @MethodSource("mistakes")
    void aPackWithAMistakeIsRefusedByFileAndName(String models, String routes, String policy, String reason) {
        assertThatThrownBy(() -> RegionPacks.parse("test", models, routes, policy))
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
