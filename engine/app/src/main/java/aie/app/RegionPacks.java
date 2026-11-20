package aie.app;

import static java.nio.charset.StandardCharsets.UTF_8;

import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Prompt;
import aie.core.gateway.internal.Route;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a region pack from the engine's jar (blueprint 12.2): {@code regions/<region>/models.yaml},
 * {@code routes.yaml} and {@code policy.yaml}, which the build copies from the repository's {@code regions/} directory. A
 * pack loads only when it passes every check (R-4), so a mistake stops the api role before it opens a port rather than
 * failing requests later, and CI checks every pack of the repository the same way:
 * <ul>
 *   <li>every field a model, a route or the policy needs is there, and no other: a misspelt field would otherwise be
 *       dropped;
 *   <li>every model's contract allows the highest data class it takes (blueprint 7.3), and the engine has its adapter;
 *   <li>every route names models of the pack, a time limit and its checks (A-15);
 *   <li>every route names one prompt version for each family of its models, and the jar has its text (M3-2);
 *   <li>the policy offers only tasks the engine has, and the routes are those of the tasks it offers, no more and no
 *       fewer (M3-4-4); ai_disclosure is required or not_required.
 * </ul>
 * Each refusal names the file, and the model or the task, so that the operator knows what to fix.
 */
final class RegionPacks {

    /**
     * The setting that names the pack, such as jp: DEPLOY_REGION, the name Brix uses too. There is no default, so a
     * deployment that leaves it out stops rather than sending its data to another region's models.
     */
    static final String SETTING = "deploy.region";
    static final String MODELS = "models.yaml";
    static final String ROUTES = "routes.yaml";
    static final String POLICY = "policy.yaml";

    /** A region's name, so that it names a directory of the pack and nothing else, such as ../ would. */
    private static final Pattern REGION = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    /** A task's name, as the modules write it, such as customer_chat. */
    private static final Pattern TASK = Pattern.compile("[a-z][a-z0-9_]*");
    /** How long a provider keeps what it is sent: none, or a number of days such as 30d. */
    private static final Pattern RETENTION = Pattern.compile("none|([1-9][0-9]{0,3})d");
    private static final List<String> MODEL_FIELDS = List.of(
            "id", "adapter", "family", "processing_region", "retention", "training", "max_data_class", "capabilities", "price");
    private static final List<String> PRICE_FIELDS = List.of("input", "output");
    private static final List<String> ROUTE_FIELDS = List.of("models", "timeout_seconds", "checks", "prompts");
    /** A prompt's fields. Only the version so far; a canary goes next to it when one comes (blueprint 8.7). */
    private static final List<String> PROMPT_FIELDS = List.of("version");
    private static final List<String> POLICY_FIELDS = List.of("tasks", "ai_disclosure");

    private RegionPacks() {
    }

    /**
     * The pack DEPLOY_REGION names.
     *
     * @throws IllegalArgumentException for a name that is no region, a region the engine has no pack for, or a pack that
     *     does not pass its checks
     */
    static RegionPack read(String region) {
        if (region == null || !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException(
                    "DEPLOY_REGION must be lower-case letters, digits and -, such as jp, not " + region);
        }
        return parse(region, resource(region, MODELS), resource(region, ROUTES), resource(region, POLICY));
    }

    private static String resource(String region, String file) {
        String path = "regions/" + region + "/" + file;
        try {
            return resource(path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("There is no region pack " + region + ": " + e.getMessage(), e);
        }
    }

    /** A file of the engine's jar. */
    private static String resource(String path) {
        try (InputStream in = RegionPacks.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("the engine has no " + path);
            }
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("the engine cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the three files of a pack and checks them. The routes are those of the tasks the policy offers: a route of a
     * task it does not offer would never be asked for, and a task it offers without a route could not be answered. A pack
     * may offer nothing; its requests are then all refused by the gate.
     *
     * @throws IllegalArgumentException naming the file and what is wrong
     */
    static RegionPack parse(String region, String modelsYaml, String routesYaml, String policyYaml) {
        Map<String, ModelSpec> models = models(region, top(region, MODELS, modelsYaml, "models"));
        Map<String, Route> routes = routes(region, top(region, ROUTES, routesYaml, "routes"), models);
        RegionPack.Policy policy = policy(region, top(region, POLICY, policyYaml, "policy"));
        for (String task : routes.keySet()) {
            if (!policy.tasks().contains(task)) {
                throw new IllegalArgumentException(
                        file(region, ROUTES) + " has a route for " + task + ", which " + POLICY + " does not offer");
            }
        }
        for (String task : policy.tasks()) {
            if (!routes.containsKey(task)) {
                throw new IllegalArgumentException(file(region, ROUTES) + " has no route for " + task + ", which " + POLICY + " offers");
            }
        }
        return new RegionPack(region, models, routes, policy);
    }

    /**
     * The region's policy (M3-4-4): the tasks it offers, each one the engine has, and whether an answer must say that an AI
     * wrote it. An empty list of tasks offers nothing.
     */
    private static RegionPack.Policy policy(String region, Object node) {
        String where = file(region, POLICY);
        Map<String, Object> policy = map(node, where + ": policy");
        fields(policy, POLICY_FIELDS, POLICY_FIELDS, where + ": policy");
        if (!(policy.get("tasks") instanceof List<?> listed)) {
            throw new IllegalArgumentException(where + ": tasks must be a list of the tasks the region offers, not " + policy.get("tasks"));
        }
        Set<String> tasks = new LinkedHashSet<>();
        for (Object item : listed) {
            String task = text(item, where + ": each task");
            if (!TASK.matcher(task).matches()) {
                throw new IllegalArgumentException(where + ": a task is lower-case letters, digits and _, not " + task);
            }
            if (!Models.TASKS.contains(task)) {
                throw new IllegalArgumentException(where + " offers " + task + ", which the engine does not have");
            }
            if (!tasks.add(task)) {
                throw new IllegalArgumentException(where + " offers " + task + " twice");
            }
        }
        Object disclosure = policy.get("ai_disclosure");
        RegionPack.AiDisclosure aiDisclosure = Arrays.stream(RegionPack.AiDisclosure.values())
                .filter(value -> value.wireName().equals(disclosure)).findFirst().orElseThrow(() -> new IllegalArgumentException(
                        where + ": ai_disclosure must be required or not_required, not " + disclosure));
        return new RegionPack.Policy(tasks, aiDisclosure);
    }

    /** The one top-level field of a file. Duplicate keys are refused, since YAML would keep the last one silently. */
    private static Object top(String region, String name, String yaml, String field) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object document;
        try {
            document = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (YAMLException e) {
            throw new IllegalArgumentException(file(region, name) + " is not valid YAML: " + e.getMessage(), e);
        }
        Map<String, Object> top = map(document, file(region, name));
        fields(top, List.of(field), List.of(field), file(region, name));
        return top.get(field);
    }

    private static Map<String, ModelSpec> models(String region, Object node) {
        String where = file(region, MODELS);
        List<Object> list = list(node, where + ": models");
        Map<String, ModelSpec> models = new LinkedHashMap<>();
        for (Object item : list) {
            Map<String, Object> model = map(item, where + ": each model");
            String id = text(model.get("id"), where + ": a model's id");
            String what = where + ": the model " + id;
            fields(model, MODEL_FIELDS, MODEL_FIELDS, what);
            String adapter = text(model.get("adapter"), what + ": adapter");
            if (!Models.ADAPTERS.containsKey(adapter)) {
                throw new IllegalArgumentException(what + " needs the adapter " + adapter + ", which this engine does not have");
            }
            ModelSpec spec;
            try {
                spec = new ModelSpec(id, adapter, text(model.get("family"), what + ": family"),
                        text(model.get("processing_region"), what + ": processing_region"),
                        retentionDays(model.get("retention"), what), bool(model.get("training"), what + ": training"),
                        DataClass.fromWireName(text(model.get("max_data_class"), what + ": max_data_class")).orElseThrow(
                                () -> new IllegalArgumentException(what + ": max_data_class must be public, internal, personal or sensitive")),
                        capabilities(model.get("capabilities"), what), price(model.get("price"), what));
            } catch (IllegalArgumentException e) {
                throw e.getMessage().startsWith(where) ? e : new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
            if (models.putIfAbsent(id, spec) != null) {
                throw new IllegalArgumentException(where + " has the model " + id + " twice");
            }
        }
        return models;
    }

    private static Map<String, Route> routes(String region, Object node, Map<String, ModelSpec> models) {
        String where = file(region, ROUTES);
        Map<String, Object> tasks = map(node, where + ": routes");
        Map<String, Route> routes = new LinkedHashMap<>();
        tasks.forEach((task, value) -> {
            if (!TASK.matcher(task).matches()) {
                throw new IllegalArgumentException(where + ": a task is lower-case letters, digits and _, not " + task);
            }
            String what = where + ": the route of " + task;
            Map<String, Object> route = map(value, what);
            fields(route, ROUTE_FIELDS, ROUTE_FIELDS, what);
            List<String> names = new ArrayList<>();
            for (Object model : list(route.get("models"), what + ": models")) {
                String name = text(model, what + ": each model");
                if (!models.containsKey(name)) {
                    throw new IllegalArgumentException(what + " names the model " + name + ", which " + MODELS + " does not have");
                }
                names.add(name);
            }
            Object seconds = route.get("timeout_seconds");
            if (!(seconds instanceof Integer number) || number <= 0) {
                throw new IllegalArgumentException(what + ": timeout_seconds must be a whole number above zero, not " + seconds);
            }
            CheckMode checks = CheckMode.fromWireName(text(route.get("checks"), what + ": checks")).orElseThrow(
                    () -> new IllegalArgumentException(what + ": checks must be sentence, full or input, not " + route.get("checks")));
            routes.put(task, new Route(names, Duration.ofSeconds(number), checks, prompts(task, what, route.get("prompts"), names, models)));
        });
        return routes;
    }

    /**
     * Each model's prompt. The route names one version for each family of its models, as {@code family: {version:
     * task/family/vN}}, and no other family; the version is for the route's own task and that family, and its text is
     * in the jar, from the repository's prompts/ (blueprint 8.7).
     */
    private static Map<String, Prompt> prompts(String task, String what, Object node, List<String> names,
            Map<String, ModelSpec> models) {
        Map<String, Object> named = map(node, what + ": prompts");
        Set<String> families = new TreeSet<>();
        names.forEach(name -> families.add(models.get(name).family()));
        for (String family : named.keySet()) {
            if (!families.contains(family)) {
                throw new IllegalArgumentException(what + ": prompts names the family " + family + ", which none of its models is");
            }
        }
        Map<String, Prompt> byFamily = new HashMap<>();
        for (String family : families) {
            String where = what + ": the prompt of " + family;
            if (!named.containsKey(family)) {
                throw new IllegalArgumentException(what + " needs a prompt for the family " + family);
            }
            Map<String, Object> prompt = map(named.get(family), where);
            fields(prompt, PROMPT_FIELDS, PROMPT_FIELDS, where);
            String version = text(prompt.get("version"), where + ": version");
            if (!version.matches(Pattern.quote(task + "/" + family + "/") + "v[1-9][0-9]{0,3}")) {
                throw new IllegalArgumentException(where + ": version must be " + task + "/" + family + "/v and a number, not " + version);
            }
            try {
                byFamily.put(family, new Prompt(version, resource("prompts/" + version + ".txt")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
        }
        Map<String, Prompt> byModel = new LinkedHashMap<>();
        names.forEach(name -> byModel.put(name, byFamily.get(models.get(name).family())));
        return byModel;
    }

    private static int retentionDays(Object value, String what) {
        Matcher retention = RETENTION.matcher(text(value, what + ": retention"));
        if (!retention.matches()) {
            throw new IllegalArgumentException(what + ": retention must be none or a number of days such as 30d, not " + value);
        }
        return retention.group(1) == null ? 0 : Integer.parseInt(retention.group(1));
    }

    private static Set<ModelSpec.Capability> capabilities(Object value, String what) {
        Set<ModelSpec.Capability> capabilities = EnumSet.noneOf(ModelSpec.Capability.class);
        for (Object item : list(value, what + ": capabilities")) {
            String name = text(item, what + ": each capability");
            capabilities.add(ModelSpec.Capability.fromWireName(name).orElseThrow(() -> new IllegalArgumentException(
                    what + " has the unknown capability " + name)));
        }
        return capabilities;
    }

    private static ModelSpec.Price price(Object value, String what) {
        Map<String, Object> price = map(value, what + ": price");
        fields(price, PRICE_FIELDS, PRICE_FIELDS, what + ": price");
        return new ModelSpec.Price(number(price.get("input"), what + ": price input"),
                number(price.get("output"), what + ": price output"));
    }

    /** Refuses a field that is not allowed, and one that is required but missing. */
    private static void fields(Map<String, Object> node, List<String> allowed, List<String> required, String what) {
        for (String key : node.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(what + " has the unknown field " + key);
            }
        }
        for (String key : required) {
            if (!node.containsKey(key)) {
                throw new IllegalArgumentException(what + " needs the field " + key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node, String what) {
        if (!(node instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException(what + " must be a map of names to values");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object node, String what) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(what + " must be a list of at least one item");
        }
        return (List<Object>) list;
    }

    private static String text(Object node, String what) {
        if (!(node instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(what + " must be text, not " + node);
        }
        return text;
    }

    private static boolean bool(Object node, String what) {
        if (!(node instanceof Boolean value)) {
            throw new IllegalArgumentException(what + " must be yes or no, not " + node);
        }
        return value;
    }

    private static BigDecimal number(Object node, String what) {
        if (!(node instanceof Integer || node instanceof Long || node instanceof Double)) {
            throw new IllegalArgumentException(what + " must be a number, not " + node);
        }
        return new BigDecimal(node.toString());
    }

    private static String file(String region, String name) {
        return "regions/" + region + "/" + name;
    }
}
