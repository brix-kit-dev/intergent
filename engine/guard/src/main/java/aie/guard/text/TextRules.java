package aie.guard.text;

import aie.guard.text.SourceText.Token;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The red lines a class graph cannot see, checked on the text of the engine's main sources (blueprint 16.1): every
 * Java file under {@code src/main/java} and every SQL file under {@code src/main/resources}, in every module but the
 * guard, which names what it forbids. Only identifiers and string literals count; comments, tests and docs are not
 * read. A-3 and A-6 leave adapters out, A-5 reads them too, A-4's table rules read a module's own migrations and code,
 * and A-13 leaves out the database adapter alone. The owner approved the scope and the lists on 2026-11-09, and the
 * lists grow as models and needs do.
 */
public final class TextRules {

    /**
     * A-3: business code names no model, so that changing models is a matter of the gateway's configuration. These
     * are the model families of the major vendors, read without regard to case. A family must start a word, so
     * {@code ollama} is not {@code llama}.
     */
    static final Pattern MODEL_NAME = Pattern.compile("(?i)(?<![a-z0-9])(gpt-|chatgpt|o[134]-|claude-|gemini-|gemma|llama"
            + "|mistral|mixtral|codestral|qwen|deepseek|command-r|text-embedding-)");
    /** Adapters talk to vendors, so they may name models, and so will the gateway's configuration once it exists. */
    private static final String ADAPTERS = "adapters/";
    /**
     * A-5: the engine never branches on a region; a region's differences come from its region pack. A string that is
     * one of these codes, in any case, is a region in the code. The approved list also had "eu", but that is the type
     * prefix of end user IDs ({@code Ids.next("eu")}), so it is left out; code that branches on Europe still needs a
     * region name, which the next rule finds.
     */
    static final Set<String> REGION_CODES = Set.of("cn", "jp", "us");
    /** A-5: a name or a text with region as a word, such as getRegion or REGION_PACK. Only a region pack loader may. */
    static final Pattern REGION_WORD = Pattern.compile("(?i)(?<![a-z0-9])region");
    /**
     * A-6: the engine knows no host and no industry; a host's world reaches it only as data, such as a session's
     * context. These are the host's own name and words that belong to one industry. A word counts with its plural and
     * only as a whole word, so cartridge is not cart. "order" is left out, because Spring and plain English use it
     * everywhere. Adapters may speak an outside system's words.
     */
    static final Pattern HOST_WORD = Pattern.compile("(?i)(?<![a-z0-9])(brix|cart|checkout|sku|invoice|shipment|booking"
            + "|reservation|hotel|patient|student|loan)(?:e?s)?(?![a-z])");
    /** A-5: the country constants of java.util.Locale. Its language constants, such as JAPANESE, name no region. */
    static final Set<String> LOCALE_COUNTRIES = Set.of(
            "CHINA", "PRC", "TAIWAN", "JAPAN", "KOREA", "US", "UK", "CANADA", "CANADA_FRENCH", "FRANCE", "GERMANY", "ITALY");

    /**
     * A-4: what a module's migration creates under its own name carries the module's prefix, such as tenancy_tenants.
     * Policies and triggers are named within their table, so they are left out.
     */
    static final Set<String> PREFIXED_KINDS = Set.of("TABLE", "VIEW", "SEQUENCE", "FUNCTION", "PROCEDURE", "INDEX", "TYPE", "DOMAIN");
    /** Words that may stand between CREATE and the kind, or between the kind and the name. */
    private static final Set<String> CREATE_WORDS = Set.of(
            "OR", "REPLACE", "UNIQUE", "UNLOGGED", "TEMP", "TEMPORARY", "MATERIALIZED", "CONCURRENTLY", "IF", "NOT", "EXISTS");
    /** A module's migrations live in a folder named after it, and Flyway keeps a history per folder. */
    private static final Pattern MIGRATION = Pattern.compile(".*/src/main/resources/db/migration/([a-z0-9]+)/[^/]+\\.sql");
    /** A module's code lives in a package named after it (blueprint 5.2). */
    private static final Pattern MODULE_CODE = Pattern.compile(".*/src/main/java/aie/(?:core|modules)/([a-z0-9]+)/.+\\.java");
    private static final Pattern SQL_WORD = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * A-13: the tenant goes into a transaction from the database adapter alone, as the transaction starts (blueprint
     * 5.5 and 7.2). Other code may read it with current_setting, as the policies do, but SQL that wrote it inside a
     * tenant transaction would switch tenant.
     */
    static final String TENANT_SETTING = "app.tenant_id";
    /**
     * A-13: set_config writes a setting whose name may only be known at run time, so only the adapter may use it. An
     * UPDATE of pg_settings cannot reach app.tenant_id, because PostgreSQL leaves custom settings out of that view.
     */
    static final String SETTING_WRITER = "set_config";
    /** The adapter behind DatabasePort, which writes the tenant. A second database adapter would join it here. */
    static final String DATABASE_ADAPTER = "adapters/store-postgres/";

    /** An object a migration creates under a name of its own. */
    record Created(String kind, String name, int line) {
    }

    private TextRules() {
    }

    /** Every problem in the engine at this root, as one line each, naming the red line, the file and the line. */
    public static List<String> violations(Path engine) throws IOException {
        List<Path> files = sources(engine);
        Set<String> modules = new TreeSet<>();
        files.forEach(file -> moduleOf(relative(engine, file)).ifPresent(modules::add));
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            String path = relative(engine, file);
            String text = Files.readString(file);
            List<Token> tokens = path.endsWith(".sql") ? SourceText.sql(text) : SourceText.java(text);
            moduleOf(path).ifPresent(module -> problems.addAll(tableRules(path, module, modules, tokens)));
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                String where = path + ":" + token.line() + ": ";
                if (!path.startsWith(ADAPTERS)) {
                    modelName(token).ifPresent(name -> problems.add("A-3: " + where + "model name " + name));
                    hostWord(token).ifPresent(found -> problems.add("A-6: " + where + found));
                }
                region(token, i == 0 ? null : tokens.get(i - 1)).ifPresent(found -> problems.add("A-5: " + where + found));
            }
            if (!path.startsWith(DATABASE_ADAPTER)) {
                problems.addAll(tenantContext(path, tokens));
            }
        }
        return problems;
    }

    /** The files the rules read: main Java and SQL of every module but the guard, never anything built. */
    static List<Path> sources(Path engine) throws IOException {
        try (Stream<Path> files = Files.walk(engine)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String path = relative(engine, file);
                        return !path.startsWith("guard/") && !path.contains("/target/") && !path.contains("/node_modules/")
                                && (path.contains("/src/main/java/") && path.endsWith(".java")
                                        || path.contains("/src/main/resources/") && path.endsWith(".sql"));
                    })
                    .sorted()
                    .toList();
        }
    }

    /** The model family in the token, with the word it starts, or empty. A camel-case name is read word by word. */
    static Optional<String> modelName(Token token) {
        String text = words(token);
        Matcher matcher = MODEL_NAME.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int end = matcher.end();
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || "-._".indexOf(text.charAt(end)) >= 0)) {
            end++;
        }
        return Optional.of(text.substring(matcher.start(), end));
    }

    /**
     * What makes the token a region in the code, or empty: a region code as a string, a country constant of Locale
     * right after the name Locale, or region as a word. {@code previous} is the token before, or null.
     */
    static Optional<String> region(Token token, Token previous) {
        if (token.kind() == SourceText.Kind.STRING && REGION_CODES.contains(token.text().toLowerCase(Locale.ROOT))) {
            return Optional.of("region code \"" + token.text() + "\"");
        }
        if (token.kind() == SourceText.Kind.IDENTIFIER && LOCALE_COUNTRIES.contains(token.text())
                && previous != null && previous.kind() == SourceText.Kind.IDENTIFIER && previous.text().equals("Locale")) {
            return Optional.of("country constant Locale." + token.text());
        }
        if (REGION_WORD.matcher(words(token)).find()) {
            return Optional.of("region in " + (token.kind() == SourceText.Kind.IDENTIFIER ? "the name " : "the text ") + token.text());
        }
        return Optional.empty();
    }

    /** The host or industry word in the token and where it was, or empty. A camel-case name is read word by word. */
    static Optional<String> hostWord(Token token) {
        Matcher matcher = HOST_WORD.matcher(words(token));
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of("host or industry word " + matcher.group(1) + " in "
                + (token.kind() == SourceText.Kind.IDENTIFIER ? "the name " : "the text ") + token.text());
    }

    /** A name split where its camel case changes, so each word starts as a word; a string as it is. */
    private static String words(Token token) {
        return token.kind() == SourceText.Kind.IDENTIFIER ? token.text().replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ") : token.text();
    }

    /**
     * A-4's table rules for one file of a module. A migration names what it creates with the module's prefix, and no
     * SQL of the module, in a migration or in a Java string, reaches a name with another module's prefix: no read,
     * no write and no foreign key across modules. Java names are code, not SQL, so only its strings count.
     */
    static List<String> tableRules(String path, String module, Set<String> modules, List<Token> tokens) {
        List<String> problems = new ArrayList<>();
        boolean sql = path.endsWith(".sql");
        for (int i = 0; sql && i < tokens.size(); i++) {
            created(tokens, i).filter(object -> !object.name().toLowerCase(Locale.ROOT).startsWith(module + "_"))
                    .ifPresent(object -> problems.add("A-4: " + path + ":" + object.line() + ": " + object.kind() + " "
                            + object.name() + " has no " + module + "_ prefix"));
        }
        for (Token token : tokens) {
            if (!sql && token.kind() != SourceText.Kind.STRING) {
                continue;
            }
            Matcher words = SQL_WORD.matcher(token.text());
            while (words.find()) {
                String word = words.group().toLowerCase(Locale.ROOT);
                for (String other : modules) {
                    if (!other.equals(module) && word.startsWith(other + "_")) {
                        problems.add("A-4: " + path + ":" + token.line() + ": reaches " + words.group() + " of module " + other);
                    }
                }
            }
        }
        return problems;
    }

    /** The object a CREATE at this token makes under a name of its own, or empty for anything else. */
    static Optional<Created> created(List<Token> tokens, int at) {
        if (!isWord(tokens, at, "CREATE")) {
            return Optional.empty();
        }
        int next = skipCreateWords(tokens, at + 1);
        if (next >= tokens.size() || tokens.get(next).kind() != SourceText.Kind.IDENTIFIER) {
            return Optional.empty();
        }
        String kind = tokens.get(next).text().toUpperCase(Locale.ROOT);
        if (!PREFIXED_KINDS.contains(kind)) {
            return Optional.empty();
        }
        int name = skipCreateWords(tokens, next + 1);
        if (name >= tokens.size() || tokens.get(name).kind() != SourceText.Kind.IDENTIFIER || isWord(tokens, name, "ON")) {
            return Optional.empty();
        }
        return Optional.of(new Created(kind.toLowerCase(Locale.ROOT), tokens.get(name).text(), tokens.get(name).line()));
    }

    private static int skipCreateWords(List<Token> tokens, int at) {
        while (at < tokens.size() && tokens.get(at).kind() == SourceText.Kind.IDENTIFIER
                && CREATE_WORDS.contains(tokens.get(at).text().toUpperCase(Locale.ROOT))) {
            at++;
        }
        return at;
    }

    private static boolean isWord(List<Token> tokens, int at, String word) {
        return at < tokens.size() && tokens.get(at).kind() == SourceText.Kind.IDENTIFIER && tokens.get(at).text().equalsIgnoreCase(word);
    }

    /**
     * A-13 for one file outside the database adapter: SQL that writes app.tenant_id, or could. A Java string is read
     * as SQL, and a Java string that is just the name counts too, since code only needs it to build such SQL.
     */
    static List<String> tenantContext(String path, List<Token> tokens) {
        List<String> problems = new ArrayList<>();
        if (path.endsWith(".sql")) {
            settingWrites(path, tokens, 0, problems);
            return problems;
        }
        for (Token token : tokens) {
            if (token.kind() != SourceText.Kind.STRING) {
                continue;
            }
            if (token.text().equalsIgnoreCase(TENANT_SETTING)) {
                problems.add("A-13: " + path + ":" + token.line() + ": names " + TENANT_SETTING + " outside current_setting");
            }
            settingWrites(path, SourceText.sql(token.text()), token.line() - 1, problems);
        }
        return problems;
    }

    /**
     * Writes of the tenant in SQL whose first line is line {@code offset + 1} of the file: SET or RESET of
     * app.tenant_id, as a statement or in the SET of a function or a role; set_config; and the name as a string
     * anywhere but in current_setting. A string inside the SQL is read as SQL too, with its doubled quotes made
     * single, since it may be a statement that EXECUTE runs.
     */
    private static void settingWrites(String path, List<Token> tokens, int offset, List<String> problems) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int line = offset + token.line();
            String where = "A-13: " + path + ":" + line + ": ";
            if (isWord(tokens, i, SETTING_WRITER)) {
                problems.add(where + "uses " + token.text() + ", which can write any setting");
            } else if (token.kind() == SourceText.Kind.STRING && token.text().equalsIgnoreCase(TENANT_SETTING)
                    && !(i > 0 && isWord(tokens, i - 1, "current_setting"))) {
                problems.add(where + "names " + TENANT_SETTING + " outside current_setting");
            } else if ((isWord(tokens, i, "SET") || isWord(tokens, i, "RESET")) && namesTenantSetting(tokens, afterScope(tokens, i + 1))) {
                problems.add(where + "writes " + TENANT_SETTING + " with " + token.text());
            }
            if (token.kind() == SourceText.Kind.STRING) {
                settingWrites(path, SourceText.sql(token.text().replace("''", "'")), line - 1, problems);
            }
        }
    }

    /** Past the LOCAL or SESSION after a SET, when there is one. */
    private static int afterScope(List<Token> tokens, int at) {
        return isWord(tokens, at, "LOCAL") || isWord(tokens, at, "SESSION") ? at + 1 : at;
    }

    /** Whether app.tenant_id starts at this token: as two names, each perhaps quoted, or as one quoted name. */
    private static boolean namesTenantSetting(List<Token> tokens, int at) {
        return isWord(tokens, at, "app") && isWord(tokens, at + 1, "tenant_id") || isWord(tokens, at, TENANT_SETTING);
    }

    /** The module a file belongs to: its migration folder, or its package under aie.core or aie.modules. */
    static Optional<String> moduleOf(String path) {
        for (Pattern pattern : List.of(MIGRATION, MODULE_CODE)) {
            Matcher matcher = pattern.matcher(path);
            if (matcher.matches()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private static String relative(Path engine, Path file) {
        return engine.relativize(file).toString().replace('\\', '/');
    }
}
