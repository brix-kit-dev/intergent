package aie.app;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** One image, several roles: the role comes from a start argument (C-9). */
enum EngineRole {
    API,
    WORKER,
    MIGRATE,
    ADMIN;

    private static final String PREFIX = "--role=";

    static EngineRole parse(String[] args) {
        List<String> given = Arrays.stream(args)
                .filter(arg -> arg.startsWith(PREFIX))
                .map(arg -> arg.substring(PREFIX.length()))
                .toList();
        if (given.isEmpty()) {
            throw new IllegalArgumentException("--role is required. " + usage());
        }
        if (given.size() > 1) {
            throw new IllegalArgumentException("--role was given more than once. " + usage());
        }
        String value = given.getFirst();
        return Arrays.stream(values())
                .filter(role -> role.argument().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + value + ". " + usage()));
    }

    String argument() {
        return name().toLowerCase(Locale.ROOT);
    }

    private static String usage() {
        return "Use " + PREFIX + Arrays.stream(values()).map(EngineRole::argument).collect(Collectors.joining("|"));
    }
}
