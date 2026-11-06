package aie.guard.contract;

import static aie.guard.contract.Contract.list;
import static aie.guard.contract.Contract.map;

import aie.guard.contract.Contract.Operation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A-7: what a client written against the baseline would notice in the current contract.
 *
 * <p>This follows blueprint 6.2. New fields, optional parameters, event types and enum values are not breaking.
 * Removing or renaming is. A change of meaning leaves no trace in the file, so reviews have to catch that.
 */
public final class ContractDiff {

    private enum Direction {
        /** What a client sends: it must stay accepted. */
        REQUEST,
        /** What a client reads: it must stay there. */
        RESPONSE
    }

    private final Contract baseline;
    private final Contract current;
    private final List<String> problems = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();

    private ContractDiff(Contract baseline, Contract current) {
        this.baseline = baseline;
        this.current = current;
    }

    public static List<String> breakingChanges(Path baseline, Path current) throws IOException {
        return breakingChanges(Contract.read(baseline), Contract.read(current));
    }

    static List<String> breakingChanges(Contract baseline, Contract current) {
        ContractDiff diff = new ContractDiff(baseline, current);
        diff.compare();
        return diff.problems;
    }

    private void compare() {
        Map<String, Operation> operations = new LinkedHashMap<>();
        current.operations().forEach(operation -> operations.put(operation.name(), operation));
        for (Operation old : baseline.operations()) {
            Operation now = operations.get(old.name());
            if (now == null) {
                report("removed operation " + old.name());
            } else {
                compareOperation(old, now);
            }
        }
        Map<String, Object> events = map(current.root().get("x-stream-events"));
        map(baseline.root().get("x-stream-events")).forEach((name, schema) -> {
            if (!events.containsKey(name)) {
                report("removed event " + name);
            } else {
                compareSchema("event " + name, map(schema), map(events.get(name)), Direction.RESPONSE);
            }
        });
    }

    private void compareOperation(Operation old, Operation now) {
        String name = old.name();
        Set<Object> callers = new HashSet<>(list(now.body().get("x-callers")));
        list(old.body().get("x-callers")).stream()
                .filter(caller -> !callers.contains(caller))
                .forEach(caller -> report(name + " no longer accepts caller " + caller));
        compareParameters(name, old, now);
        compareRequestBody(name, map(old.body().get("requestBody")), map(now.body().get("requestBody")));
        Map<String, Object> responses = map(now.body().get("responses"));
        map(old.body().get("responses")).forEach((status, response) -> {
            if (!responses.containsKey(status)) {
                report(name + " removed response " + status);
            } else {
                compareResponse(name + " " + status, map(response), map(responses.get(status)));
            }
        });
    }

    private void compareParameters(String name, Operation old, Operation now) {
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        now.parameters().forEach(parameter -> parameters.put(key(parameter), parameter));
        Set<String> known = new HashSet<>();
        for (Map<String, Object> parameter : old.parameters()) {
            known.add(key(parameter));
            Map<String, Object> changed = parameters.get(key(parameter));
            if (changed == null) {
                report(name + " removed parameter " + key(parameter));
                continue;
            }
            if (isTrue(changed.get("required")) && !isTrue(parameter.get("required"))) {
                report(name + " made parameter " + key(parameter) + " required");
            }
            compareSchema(name + " parameter " + key(parameter), map(parameter.get("schema")), map(changed.get("schema")), Direction.REQUEST);
        }
        parameters.forEach((key, parameter) -> {
            if (!known.contains(key) && isTrue(parameter.get("required"))) {
                report(name + " added required parameter " + key);
            }
        });
    }

    private void compareRequestBody(String name, Map<String, Object> oldBody, Map<String, Object> newBody) {
        Map<String, Object> old = baseline.resolve(oldBody);
        Map<String, Object> now = current.resolve(newBody);
        if (isTrue(now.get("required")) && !isTrue(old.get("required"))) {
            report(name + " now requires a request body");
        }
        Map<String, Object> content = map(now.get("content"));
        map(old.get("content")).forEach((mediaType, media) -> {
            if (!content.containsKey(mediaType)) {
                report(name + " no longer accepts " + mediaType);
            } else {
                compareSchema(name + " body", map(map(media).get("schema")), map(map(content.get(mediaType)).get("schema")), Direction.REQUEST);
            }
        });
    }

    private void compareResponse(String name, Map<String, Object> oldResponse, Map<String, Object> newResponse) {
        Map<String, Object> old = baseline.resolve(oldResponse);
        Map<String, Object> now = current.resolve(newResponse);
        Map<String, Object> headers = map(now.get("headers"));
        map(old.get("headers")).keySet().stream()
                .filter(header -> !headers.containsKey(header))
                .forEach(header -> report(name + " removed header " + header));
        Map<String, Object> content = map(now.get("content"));
        map(old.get("content")).forEach((mediaType, media) -> {
            if (!content.containsKey(mediaType)) {
                report(name + " removed " + mediaType);
            } else {
                compareSchema(name, map(map(media).get("schema")), map(map(content.get(mediaType)).get("schema")), Direction.RESPONSE);
            }
        });
    }

    private void compareSchema(String where, Map<String, Object> oldSchema, Map<String, Object> newSchema, Direction direction) {
        boolean named = oldSchema.containsKey("$ref") && newSchema.containsKey("$ref");
        if (named && !visited.add(direction + " " + oldSchema.get("$ref") + " " + newSchema.get("$ref"))) {
            return;
        }
        Map<String, Object> old = baseline.flatten(oldSchema);
        Map<String, Object> now = current.flatten(newSchema);
        compareTypes(where, old, now, direction);
        if (!String.valueOf(old.get("format")).equals(String.valueOf(now.get("format")))) {
            report(where + " changed format from " + old.get("format") + " to " + now.get("format"));
        }
        compareValues(where, old, now, direction);
        if (direction == Direction.REQUEST) {
            compareLimits(where, old, now);
        }
        compareProperties(where, old, now, direction);
        if (old.get("items") instanceof Map<?, ?> && now.get("items") instanceof Map<?, ?>) {
            compareSchema(where + "[]", map(old.get("items")), map(now.get("items")), direction);
        }
        if (old.get("additionalProperties") instanceof Map<?, ?> && now.get("additionalProperties") instanceof Map<?, ?>) {
            compareSchema(where + "{}", map(old.get("additionalProperties")), map(now.get("additionalProperties")), direction);
        }
    }

    private void compareTypes(String where, Map<String, Object> old, Map<String, Object> now, Direction direction) {
        Set<Object> oldTypes = types(old);
        Set<Object> newTypes = types(now);
        boolean compatible = direction == Direction.REQUEST ? newTypes.containsAll(oldTypes) : oldTypes.containsAll(newTypes);
        if (!compatible) {
            report(where + " changed type from " + oldTypes + " to " + newTypes);
        }
    }

    private void compareValues(String where, Map<String, Object> old, Map<String, Object> now, Direction direction) {
        Set<Object> values = new HashSet<>(list(now.get("enum")));
        values.addAll(list(now.get("x-extensible-enum")));
        for (String keyword : List.of("enum", "x-extensible-enum")) {
            list(old.get(keyword)).stream()
                    .filter(value -> !values.contains(value))
                    .forEach(value -> report(where + " removed value " + value));
        }
        if (direction == Direction.REQUEST && now.containsKey("enum") && !old.containsKey("enum")) {
            report(where + " closed its list of values");
        }
    }

    /** A value that was valid must stay valid, so request limits can only widen. */
    private void compareLimits(String where, Map<String, Object> old, Map<String, Object> now) {
        for (String keyword : List.of("maxLength", "maximum", "maxItems", "maxProperties")) {
            if (now.get(keyword) instanceof Number limit
                    && (!(old.get(keyword) instanceof Number before) || limit.doubleValue() < before.doubleValue())) {
                report(where + " tightened " + keyword);
            }
        }
        for (String keyword : List.of("minLength", "minimum", "minItems", "minProperties")) {
            if (now.get(keyword) instanceof Number limit
                    && (!(old.get(keyword) instanceof Number before) || limit.doubleValue() > before.doubleValue())) {
                report(where + " tightened " + keyword);
            }
        }
        if (now.containsKey("pattern") && !now.get("pattern").equals(old.get("pattern"))) {
            report(where + " changed its pattern");
        }
    }

    private void compareProperties(String where, Map<String, Object> old, Map<String, Object> now, Direction direction) {
        Map<String, Object> properties = map(now.get("properties"));
        Set<Object> oldRequired = new HashSet<>(list(old.get("required")));
        Set<Object> newRequired = new HashSet<>(list(now.get("required")));
        map(old.get("properties")).forEach((property, schema) -> {
            String field = where + "." + property;
            if (!properties.containsKey(property)) {
                report(field + " was removed");
            } else {
                compareSchema(field, map(schema), map(properties.get(property)), direction);
            }
        });
        if (direction == Direction.REQUEST) {
            newRequired.stream().filter(field -> !oldRequired.contains(field))
                    .forEach(field -> report(where + "." + field + " became required"));
        } else {
            oldRequired.stream().filter(field -> !newRequired.contains(field))
                    .forEach(field -> report(where + "." + field + " is no longer always there"));
        }
    }

    private static Set<Object> types(Map<String, Object> schema) {
        Object type = schema.get("type");
        return type instanceof List<?> ? new HashSet<>(list(type)) : type == null ? Set.of() : Set.of(type);
    }

    private static String key(Map<String, Object> parameter) {
        return parameter.get("in") + ":" + parameter.get("name");
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private void report(String change) {
        problems.add("A-7: " + change);
    }
}
