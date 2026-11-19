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
        write("core/src/main/resources/db/migration/tenancy/V1__a.sql", "-- region is fine here\nCREATE TABLE tenancy_t (region text, c text DEFAULT 'cn');\n");

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-5: core/src/main/java/aie/core/Route.java:2: region code \"jp\"",
                "A-5: core/src/main/java/aie/core/Route.java:3: country constant Locale.JAPAN",
                "A-5: core/src/main/java/aie/core/Route.java:4: region in the name region",
                "A-5: core/src/main/resources/db/migration/tenancy/V1__a.sql:2: region in the name region",
                "A-5: core/src/main/resources/db/migration/tenancy/V1__a.sql:2: region code \"cn\"");
    }

    /**
     * M3-1: the region pack loader may speak of regions, since it reads the pack DEPLOY_REGION names, but a region code
     * is a region in the code even there.
     */
    @Test
    void theRegionPackLoaderMaySpeakOfRegionsButNotNameOne() throws IOException {
        write("app/src/main/java/aie/app/RegionPacks.java", """
                class RegionPacks {
                    static final String SETTING = "deploy.region";
                    static RegionPack read(String region) { return null; }
                    String fallback = "jp";
                }
                """);
        write("app/src/main/java/aie/app/RegionPack.java", "record RegionPack(String region) {}");

        assertThat(TextRules.violations(engine)).containsExactly("A-5: app/src/main/java/aie/app/RegionPacks.java:4: region code \"jp\"");
    }

    /** The rest of the startup entry may name the loader's classes to load its pack, and no other region word. */
    @Test
    void theStartupEntryMayNameTheLoaderAndNothingElse() throws IOException {
        write("app/src/main/java/aie/app/ApiRole.java", """
                class ApiRole {
                    RegionPack pack = RegionPacks.read(null);
                    String regionName;
                    String text = "region";
                }
                """);

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-5: app/src/main/java/aie/app/ApiRole.java:3: region in the name regionName",
                "A-5: app/src/main/java/aie/app/ApiRole.java:4: region in the text region");
    }

    /** Outside the startup entry, even the loader's names are a region in the code, and so is a loader of another module. */
    @Test
    void onlyTheStartupEntryHasTheLoader() throws IOException {
        write("core/src/main/java/aie/core/gateway/Pick.java", "class Pick { RegionPack pack; }");
        write("http/src/main/java/aie/http/RegionPacks.java", "class RegionPacks { String region; }");

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-5: core/src/main/java/aie/core/gateway/Pick.java:1: region in the name RegionPack",
                "A-5: http/src/main/java/aie/http/RegionPacks.java:1: region in the name RegionPacks",
                "A-5: http/src/main/java/aie/http/RegionPacks.java:1: region in the name region");
    }

    /** Unlike model names, a region in an adapter is still a region in the code. */
    @Test
    void anAdapterMayNotBranchOnARegionEither() throws IOException {
        write("adapters/openai/src/main/java/aie/adapters/openai/Pick.java", "class Pick { String code = \"us\"; }");

        assertThat(TextRules.violations(engine)).containsExactly("A-5: adapters/openai/src/main/java/aie/adapters/openai/Pick.java:1: region code \"us\"");
    }

    /** Names a host's own world would bring. The word reported is the one on the list, also for a plural. */
    @ParameterizedTest
    @CsvSource({
        "ShoppingCart, Cart", "cartId, cart", "CHECKOUT_URL, CHECKOUT", "skuCode, sku", "InvoiceService, Invoice",
        "shipments, shipment", "BookingStatus, Booking", "reservations, reservation", "hotelName, hotel",
        "patientId, patient", "students, student", "loanAmount, loan", "BrixClient, Brix", "skus, sku", "invoices, invoice"
    })
    void aHostOrIndustryWordInANameIsFound(String name, String word) {
        assertThat(TextRules.hostWord(identifier(name))).contains("host or industry word " + word + " in the name " + name);
    }

    @Test
    void aHostOrIndustryWordInATextIsFound() {
        assertThat(TextRules.hostWord(string("brix.sync"))).contains("host or industry word brix in the text brix.sync");
        assertThat(TextRules.hostWord(string("invoice-123"))).contains("host or industry word invoice in the text invoice-123");
    }

    /** Only the whole word counts, and order is left out on purpose. */
    @ParameterizedTest
    @ValueSource(strings = {"cartridge", "skull", "loaned", "Brixton", "hotelier", "order", "orders", "OrderedList", "scarted", "subookings"})
    void aWordThatOnlyContainsOneIsNot(String name) {
        assertThat(TextRules.hostWord(identifier(name))).isEmpty();
    }

    @Test
    void aHostOrIndustryWordInMainCodeIsAViolation() throws IOException {
        write("core/src/main/java/aie/core/Basket.java", "class Basket {\n    String cartId;\n}\n");
        write("core/src/main/resources/db/migration/tenancy/V1__a.sql", "-- hotel is fine here\nCREATE TABLE tenancy_hotel_rooms (n int);\n");

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-6: core/src/main/java/aie/core/Basket.java:2: host or industry word cart in the name cartId",
                "A-6: core/src/main/resources/db/migration/tenancy/V1__a.sql:2: host or industry word hotel in the name tenancy_hotel_rooms");
    }

    /** An adapter to an outside system may speak its words. */
    @Test
    void anAdapterMaySpeakAnOutsideSystemsWords() throws IOException {
        write("adapters/shop/src/main/java/aie/adapters/shop/Carts.java", "class Carts { String checkoutUrl; }");

        assertThat(TextRules.violations(engine)).isEmpty();
    }

    private static final String TENANCY = "core/src/main/resources/db/migration/tenancy/";
    private static final String EVENTS = "core/src/main/resources/db/migration/events/";

    @Test
    void aModuleIsItsMigrationFolderOrItsPackage() {
        assertThat(TextRules.moduleOf(TENANCY + "V1__a.sql")).contains("tenancy");
        assertThat(TextRules.moduleOf("core/src/main/java/aie/core/tenancy/internal/Keys.java")).contains("tenancy");
        assertThat(TextRules.moduleOf("modules/src/main/java/aie/modules/knowledge/api/Search.java")).contains("knowledge");
        assertThat(TextRules.moduleOf("core/src/main/java/aie/core/Ids.java")).isEmpty();
        assertThat(TextRules.moduleOf("http/src/main/java/aie/http/Filter.java")).isEmpty();
        assertThat(TextRules.moduleOf("core/src/main/resources/db/migration/V1__loose.sql")).isEmpty();
    }

    /** Everything a module creates under a name of its own carries its prefix; policies and triggers belong to a table. */
    @Test
    void whatAModuleCreatesCarriesItsPrefix() throws IOException {
        write(TENANCY + "V1__a.sql", """
                CREATE TABLE tenancy_tenants (tenant_id text);
                CREATE TABLE orders (n int);
                CREATE UNLOGGED TABLE IF NOT EXISTS scratch (n int);
                CREATE OR REPLACE FUNCTION find_order() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$;
                CREATE UNIQUE INDEX IF NOT EXISTS orders_idx ON orders (n);
                CREATE INDEX ON tenancy_tenants (tenant_id);
                CREATE INDEX tenancy_tenants_idx ON tenancy_tenants (tenant_id);
                CREATE POLICY tenant_isolation ON tenancy_tenants USING (true);
                CREATE TRIGGER touch BEFORE UPDATE ON tenancy_tenants FOR EACH ROW EXECUTE FUNCTION tenancy_touch();
                CREATE VIEW Active AS SELECT 1;
                CREATE SEQUENCE numbers;
                CREATE TYPE mood AS ENUM ('ok');
                CREATE TABLE tenancyfoo (n int);
                """);

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-4: " + TENANCY + "V1__a.sql:2: table orders has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:3: table scratch has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:4: function find_order has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:5: index orders_idx has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:10: view Active has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:11: sequence numbers has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:12: type mood has no tenancy_ prefix",
                "A-4: " + TENANCY + "V1__a.sql:13: table tenancyfoo has no tenancy_ prefix");
    }

    /** No read, no write and no foreign key across modules, in a migration or in a function body. */
    @Test
    void aMigrationDoesNotReachAnotherModule() throws IOException {
        write(EVENTS + "V1__outbox.sql", "CREATE TABLE events_outbox (id text);\n");
        write(TENANCY + "V2__b.sql", """
                CREATE TABLE tenancy_notes (outbox_id text REFERENCES events_outbox (id), eventsful text);
                CREATE FUNCTION tenancy_count() RETURNS bigint LANGUAGE plpgsql AS $$
                BEGIN RETURN (SELECT count(*) FROM Events_Outbox); END
                $$;
                -- events_outbox in a comment is fine
                """);

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-4: " + TENANCY + "V2__b.sql:1: reaches events_outbox of module events",
                "A-4: " + TENANCY + "V2__b.sql:3: reaches Events_Outbox of module events");
    }

    /** A module's Java reaches the database through SQL strings, so those count; its names are code and do not. */
    @Test
    void aModulesSqlStringsDoNotReachAnotherModule() throws IOException {
        write(EVENTS + "V1__outbox.sql", "CREATE TABLE events_outbox (id text);\n");
        write("core/src/main/java/aie/core/tenancy/internal/Notes.java", """
                class Notes {
                    String sql = "SELECT id FROM events_outbox WHERE id = ?";
                    String events_outbox_name;
                    String own = "SELECT * FROM tenancy_tenants";
                }
                """);
        write("core/src/main/java/aie/core/events/internal/Feed.java", "class Feed { String sql = \"UPDATE tenancy_tenants SET x = 1\"; }");

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-4: core/src/main/java/aie/core/events/internal/Feed.java:1: reaches tenancy_tenants of module tenancy",
                "A-4: core/src/main/java/aie/core/tenancy/internal/Notes.java:2: reaches events_outbox of module events");
    }

    /** Code outside any module, such as the startup entry, is not a module reaching across. */
    @Test
    void codeOutsideTheModulesIsNotChecked() throws IOException {
        write(TENANCY + "V1__a.sql", "CREATE TABLE tenancy_tenants (tenant_id text);\n");
        write(EVENTS + "V1__outbox.sql", "CREATE TABLE events_outbox (id text);\n");
        write("app/src/main/java/aie/app/Check.java", "class Check { String sql = \"SELECT 1 FROM tenancy_tenants, events_outbox\"; }");

        assertThat(TextRules.violations(engine)).isEmpty();
    }

    private static final String SWITCH = "core/src/main/java/aie/core/tenancy/internal/Switch.java";
    private static final String SWITCH_SQL = TENANCY + "V2__switch.sql";

    /** Each way a module's Java can write the tenant: SQL in a string or a text block, or the name kept for later. */
    @Test
    void javaThatWritesTheTenantIsAViolation() throws IOException {
        write(SWITCH, """
                class Switch {
                    String config = "SELECT set_config('app.tenant_id', ?, true)";
                    String block = \"""
                            UPDATE tenancy_notes SET body = ?;
                            SET LOCAL app.tenant_id = ?
                            \""";
                    String reset = "reset App.Tenant_Id";
                    String name = "App.Tenant_Id";
                    // set_config('app.tenant_id', ?, true) in a comment is fine
                }
                """);

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-13: " + SWITCH + ":2: uses set_config, which can write any setting",
                "A-13: " + SWITCH + ":2: names app.tenant_id outside current_setting",
                "A-13: " + SWITCH + ":5: writes app.tenant_id with SET",
                "A-13: " + SWITCH + ":7: writes app.tenant_id with reset",
                "A-13: " + SWITCH + ":8: names app.tenant_id outside current_setting");
    }

    /** Each way a migration can write the tenant, also from SQL that a function runs as a string. */
    @Test
    void aMigrationThatWritesTheTenantIsAViolation() throws IOException {
        write(SWITCH_SQL, """
                -- SET app.tenant_id in a comment is fine
                CREATE FUNCTION tenancy_switch(wanted text) RETURNS void LANGUAGE plpgsql
                    SET app.tenant_id = 'ten_x'
                AS $$
                BEGIN
                    PERFORM SET_CONFIG('App.Tenant_Id', wanted, true);
                    EXECUTE 'SET "app.tenant_id" = ' || quote_literal(wanted);
                    EXECUTE 'SELECT set_config(''app.tenant_id'', $1, true)' USING wanted;
                END
                $$;
                ALTER ROLE engine_app SET app.tenant_id TO 'ten_x';
                SET SESSION "app"."tenant_id" TO DEFAULT;
                """);

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-13: " + SWITCH_SQL + ":3: writes app.tenant_id with SET",
                "A-13: " + SWITCH_SQL + ":6: uses SET_CONFIG, which can write any setting",
                "A-13: " + SWITCH_SQL + ":6: names app.tenant_id outside current_setting",
                "A-13: " + SWITCH_SQL + ":7: writes app.tenant_id with SET",
                "A-13: " + SWITCH_SQL + ":8: uses set_config, which can write any setting",
                "A-13: " + SWITCH_SQL + ":8: names app.tenant_id outside current_setting",
                "A-13: " + SWITCH_SQL + ":11: writes app.tenant_id with SET",
                "A-13: " + SWITCH_SQL + ":12: writes app.tenant_id with SET");
    }

    /** Reading the tenant is fine, and so is a column tenant_id of a table that the SQL calls app. */
    @Test
    void readingTheTenantIsFine() throws IOException {
        write(TENANCY + "V1__notes.sql", """
                CREATE TABLE tenancy_notes (tenant_id text NOT NULL DEFAULT current_setting('app.tenant_id', true));
                CREATE POLICY tenant_isolation ON tenancy_notes
                    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('APP.TENANT_ID', true), ''));
                CREATE FUNCTION tenancy_f() RETURNS void LANGUAGE sql SET search_path = engine, pg_temp AS $$ SELECT 1 $$;
                """);
        write("core/src/main/java/aie/core/tenancy/internal/Notes.java", """
                class Notes {
                    String mine = "SELECT current_setting('app.tenant_id')";
                    String alias = "SELECT app.application_id FROM tenancy_applications app WHERE app.tenant_id = ?";
                    String update = "UPDATE tenancy_applications SET tenant_id = ?";
                }
                """);

        assertThat(TextRules.violations(engine)).isEmpty();
    }

    /** The database adapter writes the tenant as each transaction starts; no other adapter or entry point may. */
    @Test
    void onlyTheDatabaseAdapterWritesTheTenant() throws IOException {
        write("adapters/store-postgres/src/main/java/aie/adapters/storepg/PgDatabase.java",
                "class PgDatabase { String set = \"SELECT set_config('app.tenant_id', ?, true)\"; }");
        write("adapters/openai/src/main/java/aie/adapters/openai/Calls.java", "class Calls { String set = \"SET LOCAL app.tenant_id = ?\"; }");
        write("app/src/main/java/aie/app/Boot.java", "class Boot { String set = \"RESET app.tenant_id\"; }");

        assertThat(TextRules.violations(engine)).containsExactly(
                "A-13: adapters/openai/src/main/java/aie/adapters/openai/Calls.java:1: writes app.tenant_id with SET",
                "A-13: app/src/main/java/aie/app/Boot.java:1: writes app.tenant_id with RESET");
    }
}
