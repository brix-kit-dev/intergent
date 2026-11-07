package aie.guard.contract;

import static aie.guard.contract.Contract.list;
import static aie.guard.contract.Contract.map;

import aie.guard.contract.Contract.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** The rules every public operation follows. Each problem names its red line or blueprint section. */
public final class ContractRules {

    /** Blueprint 6.10. */
    static final Set<String> ERROR_CODES = Set.of(
            "invalid_request", "unauthorized", "budget_exceeded", "forbidden", "not_found", "idempotency_key_in_use",
            "stream_expired", "idempotency_key_reused", "input_blocked", "no_eligible_model", "action_schema_invalid",
            "rate_limited", "model_unavailable", "internal_error", "output_blocked", "model_failed", "stream_interrupted");
    /** Blueprint 6.4. */
    static final Set<String> STREAM_EVENTS = Set.of(
            "message.started", "message.delta", "message.citation", "tool.started", "tool.finished",
            "handoff.requested", "message.completed", "message.blocked", "message.cancelled", "error");

    private static final Set<String> CALLERS = Set.of("server", "browser");
    private static final String PUBLIC_OPERATION = "POST /v1/anonymous-sessions";
    private static final String SERVER_SCHEME = "apiKey";
    private static final String BROWSER_SCHEME = "sessionToken";
    private static final String PROBLEM = "application/problem+json";
    private static final Pattern MODEL_PATH =
            Pattern.compile(".*/(chat|completions?|embeddings?|models?|generate|inference)(/.*)?");
    private static final Set<String> MODEL_FIELDS = Set.of(
            "model", "model_id", "temperature", "top_p", "top_k", "max_tokens", "system", "system_prompt", "prompt", "stop");
    private static final Set<String> PAGE_NUMBERS = Set.of("page", "page_size", "offset");
    private static final Pattern SNAKE_CASE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
    private static final Pattern DATE_VERSION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ID_PATTERN = Pattern.compile("\\^[a-z]+_.*");
    private static final String HOST_OWNED_ID = "ExternalUserId";

    private ContractRules() {
    }

    public static List<String> violations(Path file) throws IOException {
        return violations(Files.readString(file));
    }

    static List<String> violations(String text) {
        List<String> problems = new ArrayList<>();
        Contract contract = Contract.parse(text);
        if (text.toLowerCase(Locale.ROOT).contains("intergent")) {
            problems.add("Q-3: the product name stays out of the API");
        }
        if (!DATE_VERSION.matcher(String.valueOf(map(contract.root().get("info")).get("version"))).matches()) {
            problems.add("6.2: info.version must be the date version");
        }
        checkDocument(contract, problems);
        Set<String> operationIds = new HashSet<>();
        for (Operation operation : contract.operations()) {
            if (!operationIds.add(String.valueOf(operation.body().get("operationId")))
                    || !operation.body().containsKey("operationId")) {
                problems.add("6.2: " + operation.name() + " needs a unique operationId");
            }
            checkCallers(contract, operation, problems);
            checkHeaders(operation, problems);
            checkModelAccess(contract, operation, problems);
            checkPaging(contract, operation, problems);
            checkResponses(contract, operation, problems);
        }
        return problems;
    }

    private static void checkDocument(Contract contract, List<String> problems) {
        Map<String, Object> schemas = map(map(contract.root().get("components")).get("schemas"));
        Set<Object> codes = new HashSet<>(list(map(schemas.get("ErrorCode")).get("x-extensible-enum")));
        if (!codes.equals(ERROR_CODES)) {
            problems.add("6.10: ErrorCode must list exactly the codes of the blueprint");
        }
        Map<String, Object> events = map(contract.root().get("x-stream-events"));
        if (!events.keySet().equals(STREAM_EVENTS)) {
            problems.add("6.4: x-stream-events must list exactly the events of the blueprint");
        }
        events.forEach((name, schema) -> visitSchemas(contract, map(schema), new HashSet<>(), node -> refuseClosedEnum("event " + name, node, problems)));
        schemas.forEach((name, schema) -> checkSchema(contract, name, map(schema), problems));
    }

    private static void refuseClosedEnum(String where, Map<String, Object> schema, List<String> problems) {
        if (schema.containsKey("enum")) {
            problems.add("6.2: " + where + " uses a closed enum, so a new value would break clients: " + schema.get("enum"));
        }
    }

    /** S-10: every operation says who may call it, and its credentials agree. */
    private static void checkCallers(Contract contract, Operation operation, List<String> problems) {
        String name = operation.name();
        Set<Object> callers = new HashSet<>(list(operation.body().get("x-callers")));
        if (callers.isEmpty() || !CALLERS.containsAll(callers)) {
            problems.add("S-10: " + name + " must declare x-callers as server, browser or both");
            return;
        }
        Set<String> schemes = schemes(contract, operation);
        if (schemes.isEmpty()) {
            if (!name.equals(PUBLIC_OPERATION)) {
                problems.add("S-10: " + name + " must require a credential");
            }
            return;
        }
        if (callers.contains("server") != schemes.contains(SERVER_SCHEME)) {
            problems.add("S-10: " + name + ": x-callers and the apiKey credential disagree");
        }
        if (callers.contains("browser") != schemes.contains(BROWSER_SCHEME)) {
            problems.add("S-10: " + name + ": x-callers and the sessionToken credential disagree");
        }
    }

    private static Set<String> schemes(Contract contract, Operation operation) {
        Object security = operation.body().containsKey("security")
                ? operation.body().get("security")
                : contract.root().get("security");
        Set<String> schemes = new LinkedHashSet<>();
        list(security).forEach(requirement -> schemes.addAll(map(requirement).keySet()));
        return schemes;
    }

    private static void checkHeaders(Operation operation, List<String> problems) {
        Set<String> names = new HashSet<>();
        operation.parameters().forEach(parameter -> names.add((String) parameter.get("name")));
        if (operation.method().equals("post") && !names.contains("Idempotency-Key")) {
            problems.add("A-8: " + operation.name() + " must accept Idempotency-Key");
        }
        if (!names.contains("Engine-Version")) {
            problems.add("6.2: " + operation.name() + " must accept Engine-Version");
        }
        if (!names.contains("traceparent")) {
            problems.add("6.2: " + operation.name() + " must accept traceparent");
        }
        if (!operation.path().startsWith("/v1/")) {
            problems.add("6.2: " + operation.name() + " must live under /v1/");
        }
    }

    /** A-11: the API offers the engine's abilities, never a way through to a model. */
    private static void checkModelAccess(Contract contract, Operation operation, List<String> problems) {
        if (MODEL_PATH.matcher(operation.path()).matches()) {
            problems.add("A-11: " + operation.name() + " looks like a model passthrough");
        }
        operation.parameters().stream().map(parameter -> (String) parameter.get("name")).filter(MODEL_FIELDS::contains)
                .forEach(name -> problems.add("A-11: " + operation.name() + " takes the model setting '" + name + "'"));
        Map<String, Object> content = map(contract.resolve(map(operation.body().get("requestBody"))).get("content"));
        content.values().forEach(media -> visitProperties(contract, map(map(media).get("schema")), new HashSet<>(), property -> {
            if (MODEL_FIELDS.contains(property)) {
                problems.add("A-11: " + operation.name() + " takes the model setting '" + property + "'");
            }
        }));
    }

    private static void checkPaging(Contract contract, Operation operation, List<String> problems) {
        Set<String> names = new HashSet<>();
        operation.parameters().forEach(parameter -> names.add((String) parameter.get("name")));
        names.stream().filter(PAGE_NUMBERS::contains).forEach(name ->
                problems.add("6.2: " + operation.name() + " pages with cursors, not '" + name + "'"));
        Map<String, Object> success = contract.resolve(map(map(operation.body().get("responses")).get("200")));
        boolean returnsList = map(success.get("content")).values().stream()
                .map(media -> contract.flatten(map(map(media).get("schema"))))
                .anyMatch(schema -> map(schema.get("properties")).containsKey("data"));
        if (returnsList && !(names.contains("cursor") && names.contains("limit"))) {
            problems.add("6.2: " + operation.name() + " returns a list, so it must take cursor and limit");
        }
    }

    private static void checkResponses(Contract contract, Operation operation, List<String> problems) {
        map(operation.body().get("responses")).forEach((status, value) -> {
            Map<String, Object> response = contract.resolve(map(value));
            String where = operation.name() + " " + status;
            if (!map(response.get("headers")).containsKey("Request-Id")) {
                problems.add("6.2: " + where + " must return Request-Id");
            }
            if (status.startsWith("4") || status.startsWith("5")) {
                Map<String, Object> content = map(response.get("content"));
                if (!content.keySet().equals(Set.of(PROBLEM))) {
                    problems.add("6.2: " + where + " must be " + PROBLEM);
                }
            }
            if (status.equals("429") && !map(response.get("headers")).containsKey("Retry-After")) {
                problems.add("6.10: " + where + " must return Retry-After");
            }
            map(response.get("content")).values().forEach(media -> visitSchemas(
                    contract, map(map(media).get("schema")), new HashSet<>(), schema -> refuseClosedEnum(where, schema, problems)));
        });
    }

    private static void checkSchema(Contract contract, String name, Map<String, Object> schema, List<String> problems) {
        boolean ourId = name.endsWith("Id") && !name.equals(HOST_OWNED_ID);
        if (ourId && !(schema.get("pattern") instanceof String pattern && ID_PATTERN.matcher(pattern).matches())) {
            problems.add("6.2: schema " + name + " must have a type prefix");
        }
        map(schema.get("properties")).forEach((property, value) -> {
            if (!SNAKE_CASE.matcher(property).matches()) {
                problems.add("6.2: " + name + "." + property + " must be snake_case");
            }
            if (property.endsWith("_at") && !"date-time".equals(contract.flatten(map(value)).get("format"))) {
                problems.add("6.2: " + name + "." + property + " must be a date-time");
            }
        });
    }

    private static void visitProperties(Contract contract, Map<String, Object> schema, Set<Object> seen, Consumer<String> visitor) {
        visitSchemas(contract, schema, seen, node -> map(node.get("properties")).keySet().forEach(visitor));
    }

    private static void visitSchemas(
            Contract contract, Map<String, Object> schema, Set<Object> seen, Consumer<Map<String, Object>> visitor) {
        if (schema.containsKey("$ref") && !seen.add(schema.get("$ref"))) {
            return;
        }
        Map<String, Object> flat = contract.flatten(schema);
        visitor.accept(flat);
        map(flat.get("properties")).values().forEach(value -> visitSchemas(contract, map(value), seen, visitor));
        if (flat.get("items") instanceof Map<?, ?>) {
            visitSchemas(contract, map(flat.get("items")), seen, visitor);
        }
        if (flat.get("additionalProperties") instanceof Map<?, ?>) {
            visitSchemas(contract, map(flat.get("additionalProperties")), seen, visitor);
        }
        for (String choice : List.of("oneOf", "anyOf")) {
            list(flat.get(choice)).forEach(option -> visitSchemas(contract, map(option), seen, visitor));
        }
    }
}
