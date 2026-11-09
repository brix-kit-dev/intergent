package aie.guard.text;

import static org.assertj.core.api.Assertions.assertThat;

import aie.guard.text.SourceText.Kind;
import aie.guard.text.SourceText.Token;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The text rules on a made-up engine, file by file, so each case shows what counts and what does not. */
class TextRulesTest {

    @TempDir
    Path engine;

    private void write(String path, String text) throws IOException {
        Path file = engine.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Token string(String text) {
        return new Token(Kind.STRING, text, 1);
    }

    private static Token identifier(String text) {
        return new Token(Kind.IDENTIFIER, text, 1);
    }

    /** The name is reported as far as it goes, so the message shows what to move into configuration. */
    @ParameterizedTest
    @CsvSource({
        "gpt-4o, gpt-4o", "use GPT-4o-mini now, GPT-4o-mini", "openai/gpt-4.1, gpt-4.1", "claude-sonnet-4-5, claude-sonnet-4-5",
        "gemini-2.5-pro, gemini-2.5-pro", "o3-mini, o3-mini", "meta-llama-3, llama-3", "mistral-large, mistral-large",
        "qwen2.5, qwen2.5", "deepseek-chat, deepseek-chat", "text-embedding-3-small, text-embedding-3-small",
        "command-r-plus, command-r-plus", "chatgpt-4o-latest, chatgpt-4o-latest"
    })
    void aModelNameInAStringIsFound(String text, String name) {
        assertThat(TextRules.modelName(string(text))).contains(name);
    }

    /** A camel-case name is read word by word, so a class named after a model is found too. */
    @ParameterizedTest
    @ValueSource(strings = {"QwenClient", "useLlama3", "DEEPSEEK_KEY", "mistralPrompt"})
    void aModelNameInAnIdentifierIsFound(String text) {
        assertThat(TextRules.modelName(identifier(text))).isPresent();
    }

    /** Words that only look like one: a family must start a word, and some families need their hyphen. */
    @ParameterizedTest
    @ValueSource(strings = {"ollama", "gpt", "claude", "gemini", "o3", "radio1-x", "embedding", "ollama_runtime", "Docker"})
    void aWordThatOnlyLooksLikeAModelIsNot(String text) {
        assertThat(TextRules.modelName(string(text))).isEmpty();
    }

    @Test
    void aModelNameInMainJavaIsAViolation() throws IOException {
        write("core/src/main/java/aie/core/Pick.java", "class Pick {\n    String model = \"gpt-4o\";\n}\n");

        assertThat(TextRules.violations(engine)).containsExactly("A-3: core/src/main/java/aie/core/Pick.java:2: model name gpt-4o");
    }

    @Test
    void aModelNameInAMigrationIsAViolation() throws IOException {
        write("core/src/main/resources/db/migration/tenancy/V1__seed.sql", "-- gpt-4o in a comment is fine\nINSERT INTO t VALUES ('claude-3');\n");

        assertThat(TextRules.violations(engine))
                .containsExactly("A-3: core/src/main/resources/db/migration/tenancy/V1__seed.sql:2: model name claude-3");
    }

    /** Comments, adapters, tests, the guard and what the build made are all left out. */
    @Test
    void onlyMainCodeOutsideAdaptersCounts() throws IOException {
        write("core/src/main/java/aie/core/Doc.java", "/** For example gpt-4o. */\nclass Doc { // claude-3\n}\n");
        write("adapters/openai/src/main/java/aie/adapters/openai/Models.java", "class Models { String m = \"gpt-4o\"; }");
        write("core/src/test/java/aie/core/PickTest.java", "class PickTest { String m = \"gpt-4o\"; }");
        write("guard/src/main/java/aie/guard/Rules.java", "class Rules { String m = \"gpt-\"; }");
        write("core/target/generated-sources/aie/Gen.java", "class Gen { String m = \"gpt-4o\"; }");
        write("core/src/main/resources/application.yml", "model: gpt-4o\n");

        assertThat(TextRules.violations(engine)).isEmpty();
    }

    @Test
    void theScanReadsJavaAndSqlOfEveryModuleButTheGuard() throws IOException {
        write("core/src/main/java/A.java", "class A {}");
        write("http/src/main/java/B.java", "class B {}");
        write("adapters/store-postgres/src/main/java/C.java", "class C {}");
        write("core/src/main/resources/db/migration/tenancy/V1__a.sql", "SELECT 1;");
        write("guard/src/main/java/D.java", "class D {}");

        assertThat(TextRules.sources(engine)).extracting(file -> engine.relativize(file).toString()).containsExactly(
                "adapters/store-postgres/src/main/java/C.java", "core/src/main/java/A.java",
                "core/src/main/resources/db/migration/tenancy/V1__a.sql", "http/src/main/java/B.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn", "jp", "us", "CN", "Jp", "US"})
    void aRegionCodeAsAStringIsFound(String code) {
        assertThat(TextRules.region(string(code), null)).contains("region code \"" + code + "\"");
    }

    /** Only the whole string counts, and "eu" is the end user ID prefix, so these are fine. */
    @ParameterizedTest
    @ValueSource(strings = {"eu", "jp1", "usa", "cn-north", "us-east-1", "jpy", "status", " jp"})
    void aStringThatOnlyHoldsARegionCodeIsNot(String text) {
        assertThat(TextRules.region(string(text), null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHINA", "PRC", "TAIWAN", "JAPAN", "KOREA", "US", "UK", "CANADA", "CANADA_FRENCH", "FRANCE", "GERMANY", "ITALY"})
    void aCountryConstantOfLocaleIsFound(String constant) {
        assertThat(TextRules.region(identifier(constant), identifier("Locale"))).contains("country constant Locale." + constant);
    }

    /** Language constants name no region, and a country name is only Locale's when it follows Locale. */
    @Test
    void languageConstantsAndOtherNamesAreNot() {
        assertThat(TextRules.region(identifier("JAPANESE"), identifier("Locale"))).isEmpty();
        assertThat(TextRules.region(identifier("ROOT"), identifier("Locale"))).isEmpty();
        assertThat(TextRules.region(identifier("JAPAN"), identifier("Holidays"))).isEmpty();
        assertThat(TextRules.region(identifier("JAPAN"), null)).isEmpty();
        assertThat(TextRules.region(string("JAPAN"), identifier("Locale"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"region", "getRegion", "REGION_PACK", "regionCode", "Regional", "byRegion"})
    void regionAsAWordInANameIsFound(String name) {
        assertThat(TextRules.region(identifier(name), null)).contains("region in the name " + name);
    }

    @Test
    void regionAsAWordInATextIsFound() {
        assertThat(TextRules.region(string("engine.region"), null)).contains("region in the text engine.region");
    }

    /** Region must start a word, so these only contain its letters. */
    @ParameterizedTest
    @ValueSource(strings = {"foreignKey", "legion", "subregions", "xregion"})
    void aWordThatOnlyContainsRegionIsNot(String name) {
        assertThat(TextRules.region(identifier(name), null)).isEmpty();
    }

    @Test
    void aRegionInMainCodeIsAViolation() throws IOException {
        write("core/src/main/java/aie/core/Route.java", """
                class Route {
                    String code = "jp";
                    java.util.Locale where = java.util.Locale.JAPAN;
                    String region;
                }
                """);
        write("core/src/main/resources/db/migration/tenancy/V1__a.sql", "-- region is fine here\nCREATE TABLE t (region text, c text DEFAULT 'cn');\n");

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-5: core/src/main/java/aie/core/Route.java:2: region code \"jp\"",
                "A-5: core/src/main/java/aie/core/Route.java:3: country constant Locale.JAPAN",
                "A-5: core/src/main/java/aie/core/Route.java:4: region in the name region",
                "A-5: core/src/main/resources/db/migration/tenancy/V1__a.sql:2: region in the name region",
                "A-5: core/src/main/resources/db/migration/tenancy/V1__a.sql:2: region code \"cn\"");
    }

    /** Unlike model names, a region in an adapter is still a region in the code. */
    @Test
    void anAdapterMayNotBranchOnARegionEither() throws IOException {
        write("adapters/openai/src/main/java/aie/adapters/openai/Pick.java", "class Pick { String code = \"us\"; }");

        assertThat(TextRules.violations(engine)).containsExactly("A-5: adapters/openai/src/main/java/aie/adapters/openai/Pick.java:1: region code \"us\"");
    }
}
