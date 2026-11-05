package aie.guard;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The red lines of blueprint section 16 that ArchUnit checks. The root package is a parameter, so the
 * same rules run on the engine ("aie") and on the samples that prove they work ("sample").
 */
public final class ArchitectureRules {

    /** A rule and the red line it enforces. */
    public record NamedRule(String id, ArchRule rule) {

        @Override
        public String toString() {
            return id;
        }
    }

    private final String root;
    private final Pattern moduleName;

    private ArchitectureRules(String root) {
        this.root = root;
        this.moduleName = Pattern.compile("^(" + root + "\\.(?:core|modules)\\.[^.]+)(\\..*)?$");
    }

    public static List<NamedRule> forRoot(String root) {
        ArchitectureRules rules = new ArchitectureRules(root);
        return List.of(
                named("A-1", rules.dependenciesPointInward()),
                named("A-2-ports", rules.modelPortsOnlyInGateway()),
                named("A-2-sdks", rules.modelSdksOnlyInAdapters()),
                named("A-4-public", rules.modulesMeetOnlyThroughPublicPackages()),
                named("A-4-tx", rules.transactionsStayOutOfPublicPackages()),
                named("A-13", rules.databaseOnlyThroughPorts()),
                named("A-15", rules.trustDoesNotUseGateway()),
                named("A-17", rules.outboundHttpOnlyInAdapters()),
                named("A-18-engine", rules.engineIgnoresSdkAndProducts()),
                named("A-18-outside", rules.sdkAndProductsIgnoreEngine()),
                named("A-18-sdk", rules.sdkIgnoresProducts()),
                named("T-5", rules.systemTransactionsOnlyInTenancyAndEvents()));
    }

    /** Most packages hold no classes yet. The samples prove that each rule fires when they do. */
    private static NamedRule named(String id, ArchRule rule) {
        return new NamedRule(id, rule.allowEmptyShould(true));
    }

    private ArchRule dependenciesPointInward() {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .ensureAllClassesAreContainedInArchitectureIgnoring(inside("sdk"), inside("products"))
                .layer("L0 adapters").definedBy(inside("adapters"))
                .layer("L1 ports").definedBy(inside("ports"))
                .layer("L2 core").definedBy(inside("core"))
                .layer("L3 modules").definedBy(inside("modules"))
                .layer("L4 http").definedBy(inside("http"))
                .layer("Startup entry").definedBy(inside("app"))
                .whereLayer("L0 adapters").mayOnlyAccessLayers("L1 ports")
                .whereLayer("L1 ports").mayNotAccessAnyLayer()
                .whereLayer("L2 core").mayOnlyAccessLayers("L1 ports")
                .whereLayer("L3 modules").mayOnlyAccessLayers("L2 core", "L1 ports")
                .whereLayer("L4 http").mayOnlyAccessLayers("L3 modules", "L2 core")
                .because("A-1: dependencies point inward and only the startup entry knows the adapters");
    }

    private ArchRule modelPortsOnlyInGateway() {
        return noClasses()
                .that().resideOutsideOfPackages(
                        inside("core.gateway"), inside("ports"), inside("adapters"), inside("app"))
                .should().dependOnClassesThat().haveNameMatching(
                        root + "\\.ports\\.(Model|Embedding|Rerank|Speech|Ocr)Port(\\$.*)?")
                .because("A-2: model-class ports belong to the gateway");
    }

    private ArchRule modelSdksOnlyInAdapters() {
        return noClasses()
                .that().resideOutsideOfPackage(inside("adapters"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.ai..",
                        "com.anthropic..",
                        "com.openai..",
                        "com.google.genai..",
                        "com.google.cloud.vertexai..",
                        "software.amazon.awssdk.services.bedrock..",
                        "software.amazon.awssdk.services.bedrockruntime..",
                        "com.alibaba.dashscope..")
                .because("A-2: vendor SDKs and Spring AI appear only in adapters");
    }

    /** Blueprint 5.2: a module has one public package, and everything else in it is internal. */
    private ArchRule modulesMeetOnlyThroughPublicPackages() {
        return classes()
                .that().resideOutsideOfPackage(inside("app"))
                .should(useOnlyPublicPackagesOfOtherModules())
                .because("A-4: another module is reached only through its api package");
    }

    private ArchCondition<JavaClass> useOnlyPublicPackagesOfOtherModules() {
        return new ArchCondition<>("use only the public packages of other modules") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String own = moduleOf(item.getPackageName());
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    String owner = moduleOf(target);
                    boolean foreign = !owner.isEmpty() && !owner.equals(own);
                    if (foreign && !target.equals(owner + ".api") && !target.startsWith(owner + ".api.")) {
                        events.add(violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private String moduleOf(String packageName) {
        Matcher matcher = moduleName.matcher(packageName);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private ArchRule transactionsStayOutOfPublicPackages() {
        return noClasses()
                .that().resideInAnyPackage(root + ".core.*.api..", root + ".modules.*.api..")
                .and().resideOutsideOfPackage(root + ".core.events.api..")
                .should().dependOnClassesThat().haveNameMatching(root + "\\.ports\\.Tx(\\$.*)?")
                .because("A-4: only the events module takes a transaction through its public package");
    }

    private ArchRule databaseOnlyThroughPorts() {
        return noClasses()
                .that().resideOutsideOfPackages(inside("adapters"), inside("app"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.springframework.transaction..",
                        "jakarta.transaction..",
                        "org.postgresql..",
                        "com.kingbase8..")
                .because("A-13: modules reach the database only through DatabasePort");
    }

    private ArchRule trustDoesNotUseGateway() {
        return noClasses()
                .that().resideInAPackage(inside("core.trust"))
                .should().dependOnClassesThat().resideInAPackage(inside("core.gateway"))
                .because("A-15: the gateway calls trust, so the opposite direction would be a cycle");
    }

    private ArchRule outboundHttpOnlyInAdapters() {
        return noClasses()
                .that().resideOutsideOfPackages(inside("adapters"), inside("sdk"), inside("products"))
                .should().dependOnClassesThat().haveNameMatching(
                        "java\\.net\\.(http\\..*|HttpURLConnection|URLConnection|Socket|URL)"
                                + "|org\\.springframework\\.web\\.client\\..*"
                                + "|org\\.springframework\\.web\\.reactive\\.function\\.client\\..*"
                                + "|org\\.springframework\\.http\\.client\\..*"
                                + "|org\\.apache\\.hc\\..*"
                                + "|okhttp3\\..*")
                .because("A-17: outbound HTTP goes through adapters, which apply timeouts and SSRF checks");
    }

    private ArchRule engineIgnoresSdkAndProducts() {
        return noClasses()
                .that().resideInAnyPackage(engine())
                .should().dependOnClassesThat().resideInAnyPackage(inside("sdk"), inside("products"))
                .because("A-18: the engine knows nothing about the SDK or the products");
    }

    private ArchRule sdkAndProductsIgnoreEngine() {
        return noClasses()
                .that().resideInAnyPackage(inside("sdk"), inside("products"))
                .should().dependOnClassesThat().resideInAnyPackage(engine())
                .because("A-18: the SDK and the products reach the engine only through the public API");
    }

    private ArchRule sdkIgnoresProducts() {
        return noClasses()
                .that().resideInAPackage(inside("sdk"))
                .should().dependOnClassesThat().resideInAPackage(inside("products"))
                .because("A-18: the SDK does not depend on the products built on it");
    }

    private ArchRule systemTransactionsOnlyInTenancyAndEvents() {
        return noClasses()
                .that().resideOutsideOfPackages(
                        inside("core.tenancy"), inside("core.events"), inside("adapters"))
                .should().accessTargetWhere(target(name("inSystemTx")))
                .because("T-5: only tenancy and events open system transactions");
    }

    private String[] engine() {
        return new String[] {
            inside("ports"), inside("core"), inside("modules"), inside("http"), inside("adapters"), inside("app")
        };
    }

    private String inside(String packageName) {
        return root + "." + packageName + "..";
    }
}
