package aie.guard;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static org.assertj.core.api.Assertions.assertThat;

import aie.guard.ArchitectureRules.NamedRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves the rules on the samples: legal code passes, and each violation is caught by the right rule. */
class ArchitectureRulesTest {

    private static final JavaClasses SAMPLES = new ClassFileImporter().importPackages("sample");
    private static final List<NamedRule> RULES = ArchitectureRules.forRoot("sample");

    static Stream<JavaClass> violations() {
        return SAMPLES.stream().filter(sample -> sample.isAnnotatedWith(Violates.class));
    }

    private static Set<String> declaredBy(JavaClass sample) {
        return Set.of(sample.getAnnotationOfType(Violates.class).value());
    }

    @Test
    void theLegalSampleBreaksNoRule() {
        JavaClasses legal = SAMPLES.that(not(annotatedWith(Violates.class)));
        assertThat(legal.contain("sample.app.Main")).isTrue();
        RULES.forEach(named -> named.rule().check(legal));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void aViolationBreaksExactlyTheRulesItDeclares(JavaClass sample) {
        JavaClasses alone = SAMPLES.that(name(sample.getName()));
        Set<String> broken = RULES.stream()
                .filter(named -> named.rule().evaluate(alone).hasViolation())
                .map(NamedRule::id)
                .collect(Collectors.toSet());
        assertThat(broken).isEqualTo(declaredBy(sample));
    }

    @Test
    void everyRuleIsProvenByAViolation() {
        Set<String> proven = violations().flatMap(sample -> declaredBy(sample).stream()).collect(Collectors.toSet());
        assertThat(proven).isEqualTo(RULES.stream().map(NamedRule::id).collect(Collectors.toSet()));
    }
}
