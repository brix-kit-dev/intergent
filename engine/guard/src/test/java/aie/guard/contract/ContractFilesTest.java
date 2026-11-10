package aie.guard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the rules on the real contract. */
class ContractFilesTest {

    private static Path contracts;

    @BeforeAll
    static void findTheContract() throws URISyntaxException {
        Path testClasses = Path.of(ContractFilesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        contracts = testClasses.getParent().getParent().getParent().getParent().resolve("contracts");
    }

    @Test
    void theContractFollowsTheRules() throws IOException {
        assertThat(ContractRules.violations(contracts.resolve("openapi.yaml"))).isEmpty();
    }

    @Test
    void theContractDoesNotBreakTheBaseline() throws IOException {
        assertThat(ContractDiff.breakingChanges(contracts.resolve("baseline.yaml"), contracts.resolve("openapi.yaml"))).isEmpty();
    }

    /** Code point 23: the key names of a session's context were the one request limit A-7 did not compare. */
    @Test
    void aTighterContextKeyNameBreaksTheBaseline() throws IOException {
        String contract = Files.readString(contracts.resolve("openapi.yaml"));
        String keys = "          propertyNames:\n            minLength: 1\n            maxLength: 40\n";
        assertThat(contract).containsOnlyOnce(keys);

        assertThat(ContractDiff.breakingChanges(
                        Contract.read(contracts.resolve("baseline.yaml")),
                        Contract.parse(contract.replace(keys, keys.replace("maxLength: 40", "maxLength: 20")))))
                .containsExactly("A-7: POST /v1/sessions body.context property names tightened maxLength");
    }

    @Test
    void theContractHasOperations() throws IOException {
        assertThat(Contract.read(contracts.resolve("openapi.yaml")).operations()).hasSizeGreaterThan(5);
        assertThat(Files.readString(contracts.resolve("baseline.yaml"))).contains("/v1/conversations");
    }

    /** The generator could drop an operation without an error, for example through a tag filter. */
    @Test
    void everyOperationHasAGeneratedMethod() throws IOException {
        Path generated = contracts.resolveSibling("engine/http/target/classes/aie/http/api");
        Set<String> methods = new ClassFileImporter().importPath(generated).stream()
                .flatMap(type -> type.getMethods().stream())
                .map(JavaMethod::getName)
                .collect(Collectors.toSet());

        assertThat(methods).containsAll(
                Contract.read(contracts.resolve("openapi.yaml")).operations().stream()
                        .map(operation -> (String) operation.body().get("operationId"))
                        .toList());
    }
}
