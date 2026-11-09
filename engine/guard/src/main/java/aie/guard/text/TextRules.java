package aie.guard.text;

import aie.guard.text.SourceText.Token;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The red lines a class graph cannot see, checked on the text of the engine's main sources (blueprint 16.1): every
 * Java file under {@code src/main/java} and every SQL file under {@code src/main/resources}, in every module but the
 * guard, which names what it forbids. Only identifiers and string literals count; comments, tests and docs are not
 * read. The owner approved the scope and the lists on 2026-11-09, and the lists grow as models and needs do.
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

    private TextRules() {
    }

    /** Every problem in the engine at this root, as one line each, naming the red line, the file and the line. */
    public static List<String> violations(Path engine) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : sources(engine)) {
            String path = engine.relativize(file).toString().replace('\\', '/');
            String text = Files.readString(file);
            List<Token> tokens = path.endsWith(".sql") ? SourceText.sql(text) : SourceText.java(text);
            for (Token token : tokens) {
                if (!path.startsWith(ADAPTERS)) {
                    modelName(token).ifPresent(name -> problems.add("A-3: " + path + ":" + token.line() + ": model name " + name));
                }
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
        String text = token.kind() == SourceText.Kind.IDENTIFIER
                ? token.text().replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                : token.text();
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
}
