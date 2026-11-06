package aie.guard.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** An OpenAPI document as plain maps, with the few lookups the checks need. */
final class Contract {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final String REFERENCE_PREFIX = "#/";

    private final Map<String, Object> document;

    private Contract(Map<String, Object> document) {
        this.document = document;
    }

    /** One operation with its path, and the parameters of the path and the operation together. */
    record Operation(String method, String path, Map<String, Object> body, List<Map<String, Object>> parameters) {

        String name() {
            return method.toUpperCase(Locale.ROOT) + " " + path;
        }
    }

    static Contract read(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    static Contract parse(String text) {
        Map<String, Object> document = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        return new Contract(document);
    }

    Map<String, Object> root() {
        return document;
    }

    List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        map(document.get("paths")).forEach((path, value) -> {
            Map<String, Object> item = map(value);
            item.forEach((method, operation) -> {
                if (METHODS.contains(method)) {
                    operations.add(new Operation(method, path, map(operation), parameters(item, map(operation))));
                }
            });
        });
        return operations;
    }

    private List<Map<String, Object>> parameters(Map<String, Object> item, Map<String, Object> operation) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Object parameter : list(item.get("parameters"))) {
            parameters.add(resolve(map(parameter)));
        }
        for (Object parameter : list(operation.get("parameters"))) {
            parameters.add(resolve(map(parameter)));
        }
        return parameters;
    }

    /** Follows {@code $ref} until it reaches the object itself. */
    Map<String, Object> resolve(Map<String, Object> node) {
        Map<String, Object> current = node;
        for (int depth = 0; current.containsKey("$ref"); depth++) {
            if (depth > 20) {
                throw new IllegalArgumentException("Reference loop at " + node);
            }
            current = lookUp((String) current.get("$ref"));
        }
        return current;
    }

    /** Returns the schema with {@code allOf} merged, so the checks see one flat object. */
    Map<String, Object> flatten(Map<String, Object> schema) {
        Map<String, Object> resolved = resolve(schema);
        if (!resolved.containsKey("allOf")) {
            return resolved;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Object part : list(resolved.get("allOf"))) {
            merged.putAll(flatten(map(part)));
        }
        resolved.forEach((key, value) -> {
            if (!key.equals("allOf")) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private Map<String, Object> lookUp(String reference) {
        if (!reference.startsWith(REFERENCE_PREFIX)) {
            throw new IllegalArgumentException("Only local references are allowed: " + reference);
        }
        Object current = document;
        for (String part : reference.substring(REFERENCE_PREFIX.length()).split("/")) {
            current = map(current).get(part);
            if (current == null) {
                throw new IllegalArgumentException("Reference points at nothing: " + reference);
            }
        }
        return map(current);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }
}
