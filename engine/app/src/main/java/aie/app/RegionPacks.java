package aie.app;

import static java.nio.charset.StandardCharsets.UTF_8;

import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Route;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a region pack from the engine's jar (blueprint 12.2): {@code regions/<region>/models.yaml} and
 * {@code routes.yaml}, which the build copies from the repository's {@code regions/} directory. A pack loads only when
 * it passes every check (R-4), so a mistake stops the api role before it opens a port rather than failing requests
 * later, and CI checks every pack of the repository the same way:
 * <ul>
 *   <li>every field a model or a route needs is there, and no other: a misspelt field would otherwise be dropped;
 *   <li>every model's contract allows the highest data class it takes (blueprint 7.3), and the engine has its adapter;
 *   <li>every route names models of the pack, a time limit and its checks (A-15);
 *   <li>every task the engine asks for has a route.
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

    /** A region's name, so that it names a directory of the pack and nothing else, such as ../ would. */
    private static final Pattern REGION = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    /** A task's name, as the modules write it, such as customer_chat. */
    private static final Pattern TASK = Pattern.compile("[a-z][a-z0-9_]*");
    /** How long a provider keeps what it is sent: none, or a number of days such as 30d. */
    private static final Pattern RETENTION = Pattern.compile("none|([1-9][0-9]{0,3})d");
    private static final List<String> MODEL_FIELDS = List.of(
            "id", "adapter", "family", "processing_region", "retention", "training", "max_data_class", "capabilities", "price");
    private static final List<String> PRICE_FIELDS = List.of("input", "output");
    private static final List<String> ROUTE_FIELDS = List.of("models", "timeout_seconds", "checks");

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
        return parse(region, resource(region, MODELS), resource(region, ROUTES));
    }

    private static String resource(String region, String file) {
        String path = "regions/" + region + "/" + file;
        try (InputStream in = RegionPacks.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("There is no region pack " + region + ": the engine has no " + path);
            }
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("The engine cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the two files of a pack and checks them.
     *
     * @throws IllegalArgumentException naming the file and what is wrong
     */
    static RegionPack parse(String region, String modelsYaml, String routesYaml) {
        Map<String, ModelSpec> models = models(region, top(region, MODELS, modelsYaml, "models"));
        Map<String, Route> routes = routes(region, top(region, ROUTES, routesYaml, "routes"), models);
        for (String task : Models.TASKS) {
            if (!routes.containsKey(task)) {
                throw new IllegalArgumentException(
                        file(region, ROUTES) + " has no route for " + task + ", which the engine asks for");
            }
        }
        return new RegionPack(region, models, routes);
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
            routes.put(task, new Route(names, Duration.ofSeconds(number), checks));
        });
        if (routes.isEmpty()) {
            throw new IllegalArgumentException(where + " has no route");
        }
        return routes;
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
