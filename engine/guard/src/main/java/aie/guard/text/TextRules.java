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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The red lines a class graph cannot see, checked on the text of the engine's main sources (blueprint 16.1): every
 * Java file under {@code src/main/java} and every SQL file under {@code src/main/resources}, in every module but the
 * guard, which names what it forbids. Only identifiers and string literals count; comments, tests and docs are not
 * read. A-3 leaves adapters out, and A-5 reads them too. The owner approved the scope and the lists on 2026-11-09, and
 * the lists grow as models and needs do.
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
    /** A-5: the country constants of java.util.Locale. Its language constants, such as JAPANESE, name no region. */
    static final Set<String> LOCALE_COUNTRIES = Set.of(
            "CHINA", "PRC", "TAIWAN", "JAPAN", "KOREA", "US", "UK", "CANADA", "CANADA_FRENCH", "FRANCE", "GERMANY", "ITALY");

    private TextRules() {
    }

    /** Every problem in the engine at this root, as one line each, naming the red line, the file and the line. */
    public static List<String> violations(Path engine) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : sources(engine)) {
            String path = engine.relativize(file).toString().replace('\\', '/');
            String text = Files.readString(file);
            List<Token> tokens = path.endsWith(".sql") ? SourceText.sql(text) : SourceText.java(text);
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                String where = path + ":" + token.line() + ": ";
                if (!path.startsWith(ADAPTERS)) {
                    modelName(token).ifPresent(name -> problems.add("A-3: " + where + "model name " + name));
                }
                region(token, i == 0 ? null : tokens.get(i - 1)).ifPresent(found -> problems.add("A-5: " + where + found));
            }
        }
        return problems;
    }

    /** The files the rules read: main Java and SQL of every module but the guard, never anything built. */
    static List<Path> sources(Path engine) throws IOException {
        try (Stream<Path> files = Files.walk(engine)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String path = engine.relativize(file).toString().replace('\\', '/');
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

    /** A name split where its camel case changes, so each word starts as a word; a string as it is. */
    private static String words(Token token) {
        return token.kind() == SourceText.Kind.IDENTIFIER ? token.text().replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ") : token.text();
    }
}
