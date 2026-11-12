package aie.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import aie.guard.ArchitectureRules.NamedRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs the rules on the engine. Classes are found on disk, so a new module cannot be left out. */
class EngineArchitectureTest {

    private static final int DEEPEST_MODULE_SOURCES = 5;

    private static Path engine;
    private static JavaClasses classes;

    @BeforeAll
    static void importEngineClasses() throws IOException, URISyntaxException {
        Path testClasses = Path.of(EngineArchitectureTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        engine = testClasses.getParent().getParent().getParent();
        classes = new ClassFileImporter().importPaths(moduleDirectories(Path.of("target", "classes")));
    }

    private static List<Path> moduleDirectories(Path suffix) throws IOException {
        try (Stream<Path> paths = Files.walk(engine, DEEPEST_MODULE_SOURCES)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(suffix))
                    .filter(path -> !path.startsWith(engine.resolve("guard")))
                    .toList();
        }
    }

    private static List<String> sourceClasses() throws IOException {
        List<String> names = new ArrayList<>();
        for (Path sources : moduleDirectories(Path.of("src", "main", "java"))) {
            try (Stream<Path> files = Files.walk(sources)) {
                files.filter(file -> file.toString().endsWith(".java"))
                        .map(file -> sources.relativize(file).toString())
                        .map(file -> file.substring(0, file.length() - ".java".length()).replace('/', '.'))
                        .forEach(names::add);
            }
        }
        return names;
    }

    static List<NamedRule> rules() {
        return ArchitectureRules.forRoot("aie");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theEngineFollowsTheRule(NamedRule named) {
        named.rule().check(classes);
    }

    @Test
    void everySourceFileReachesTheRules() throws IOException {
        List<String> sources = sourceClasses();
        assertThat(sources).as("engine source files").isNotEmpty();
        assertThat(sources)
                .as("source files with no compiled class: is the module part of the build?")
                .filteredOn(source -> !classes.contain(source))
                .isEmpty();
    }

    /**
     * A-2 matches the model ports by name, with their nested types. So every engine type that the real port's call
     * reaches, generics included, must live inside the port, or the rule would let other modules use it (M2-1).
     */
    @Test
    void theModelRuleCoversEveryTypeOfTheRealPort() {
        assertThat(classes.contain("aie.ports.ModelPort")).isTrue();

        assertThat(engineTypesReachedFrom(classes.get("aie.ports.ModelPort")))
                .contains("aie.ports.ModelPort$Request", "aie.ports.ModelPort$Turn", "aie.ports.ModelPort$Result")
                .allMatch(name -> name.matches("aie\\.ports\\.ModelPort(\\$.*)?"));
    }

    /**
     * Other modules call the gateway. A port type in its API, generics, supertypes and throws included, would reach
     * them in spite of A-2, and A-2 would not see it, because they would never name it (M2-2). Every class of the API
     * package counts, also one that no method of the gateway reaches.
     */
    @Test
    void theGatewayApiHandsOutNoPortType() {
        List<JavaClass> api = classes.stream().filter(type -> type.getPackageName().equals("aie.core.gateway.api")).toList();
        Set<String> reached = new TreeSet<>();
        api.forEach(type -> {
            reached.addAll(engineTypesReachedFrom(type));
            type.getDirectDependenciesFromSelf().forEach(dependency -> reached.add(dependency.getTargetClass().getName()));
        });

        assertThat(reached).contains("aie.core.gateway.api.Gateway", "aie.core.gateway.api.Gateway$Request",
                        "aie.core.gateway.api.Gateway$Turn", "aie.core.gateway.api.Gateway$Failed")
                .noneMatch(name -> name.startsWith("aie.ports."));
    }

    /** The engine types that a type's methods and fields use, generics included, and the ones those use in turn. */
    private static Set<String> engineTypesReachedFrom(JavaClass start) {
        Set<String> reached = new TreeSet<>();
        Deque<JavaClass> open = new ArrayDeque<>(List.of(start));
        while (!open.isEmpty()) {
            JavaClass type = open.pop();
            if (!type.getName().startsWith("aie.") || !reached.add(type.getName())) {
                continue;
            }
            List<JavaType> used = new ArrayList<>();
            type.getMethods().forEach(method -> {
                used.add(method.getReturnType());
                used.addAll(method.getParameterTypes());
            });
            type.getFields().forEach(field -> used.add(field.getType()));
            used.forEach(javaType -> open.addAll(javaType.getAllInvolvedRawTypes()));
        }
        return reached;
    }

    /** A-4-tx and T-5 match by name, so a rename would switch them off without any failure. */
    @Test
    void theTransactionRulesPointAtRealNames() {
        assumeTrue(classes.contain("aie.ports.DatabasePort"), "DatabasePort does not exist yet");
        assertThat(classes.contain("aie.ports.Tx")).isTrue();
        assertThat(classes.get("aie.ports.DatabasePort").getMethods())
                .anyMatch(method -> method.getName().equals("inSystemTx"));
    }
}
