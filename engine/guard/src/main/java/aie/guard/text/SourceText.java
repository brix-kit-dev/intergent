package aie.guard.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The words of a source file that a text rule looks at: identifiers and string literals, with the line each starts on.
 * Comments are skipped, so a comment may name an example, such as an order number, without breaking a rule.
 */
final class SourceText {

    enum Kind {
        IDENTIFIER,
        STRING
    }

    /** One word of a source file. A string's text is its content as written, escapes included. */
    record Token(Kind kind, String text, int line) {
    }

    private SourceText() {
    }

    /** Java: comments, string literals with their escapes, text blocks, character literals, identifiers, numbers. */
    static List<Token> java(String source) {
        List<Token> tokens = new ArrayList<>();
        Cursor at = new Cursor(source);
        while (!at.done()) {
            char c = at.peek();
            if (at.startsWith("//")) {
                at.skipToLineEnd();
            } else if (at.startsWith("/*")) {
                at.skipPast("*/");
            } else if (at.startsWith("\"\"\"")) {
                int line = at.line;
                tokens.add(new Token(Kind.STRING, at.quoted("\"\"\"", true), line));
            } else if (c == '"') {
                int line = at.line;
                tokens.add(new Token(Kind.STRING, at.quoted("\"", true), line));
            } else if (c == '\'') {
                at.quoted("'", true);
            } else if (Character.isJavaIdentifierStart(c)) {
                int line = at.line;
                tokens.add(new Token(Kind.IDENTIFIER, at.word(), line));
            } else if (Character.isDigit(c)) {
                at.word();
            } else {
                at.next();
            }
        }
        return tokens;
    }

    /**
     * SQL: comments, string literals with doubled quotes, quoted and plain identifiers, numbers. A dollar quote only
     * marks a function body, which is code too, so the words inside it are read as SQL.
     */
    static List<Token> sql(String source) {
        List<Token> tokens = new ArrayList<>();
        Cursor at = new Cursor(source);
        while (!at.done()) {
            char c = at.peek();
            if (at.startsWith("--")) {
                at.skipToLineEnd();
            } else if (at.startsWith("/*")) {
                at.skipPast("*/");
            } else if (c == '\'') {
                int line = at.line;
                tokens.add(new Token(Kind.STRING, at.quoted("'", false), line));
            } else if (c == '"') {
                int line = at.line;
                tokens.add(new Token(Kind.IDENTIFIER, at.quoted("\"", false), line));
            } else if (c == '$') {
                at.dollarQuote();
            } else if (Character.isLetter(c) || c == '_') {
                int line = at.line;
                tokens.add(new Token(Kind.IDENTIFIER, at.word(), line));
            } else if (Character.isDigit(c)) {
                at.word();
            } else {
                at.next();
            }
        }
        return tokens;
    }

    /** A position in the source that knows its line. */
    private static final class Cursor {

        private final String source;
        private int at;
        private int line = 1;

        Cursor(String source) {
            this.source = source;
        }

        boolean done() {
            return at >= source.length();
        }

        char peek() {
            return source.charAt(at);
        }

        boolean startsWith(String text) {
            return source.startsWith(text, at);
        }

        void next() {
            if (source.charAt(at) == '\n') {
                line++;
            }
            at++;
        }

        void skipToLineEnd() {
            while (!done() && peek() != '\n') {
                next();
            }
        }

        /** Past the end mark, or to the end of the source when it never comes. */
        void skipPast(String end) {
            for (int i = 0; i < 2; i++) {
                next();
            }
            while (!done() && !startsWith(end)) {
                next();
            }
            for (int i = 0; i < end.length() && !done(); i++) {
                next();
            }
        }

        /**
         * The text between this quote and its closing one. With backslash escapes a backslash keeps the next character;
         * without, as in SQL, a doubled quote stands for one. An unclosed quote ends at the end of the source.
         */
        String quoted(String quote, boolean backslashEscapes) {
            for (int i = 0; i < quote.length(); i++) {
                next();
            }
            int start = at;
            while (!done()) {
                if (backslashEscapes && peek() == '\\' && at + 1 < source.length()) {
                    next();
                    next();
                } else if (startsWith(quote) && !backslashEscapes && source.startsWith(quote, at + quote.length())) {
                    next();
                    next();
                } else if (startsWith(quote)) {
                    String text = source.substring(start, at);
                    for (int i = 0; i < quote.length(); i++) {
                        next();
                    }
                    return text;
                } else {
                    next();
                }
            }
            return source.substring(start);
        }

        /** Letters, digits, underscores and dollar signs, as one word. */
        String word() {
            int start = at;
            while (!done() && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '$')) {
                next();
            }
            return source.substring(start, at);
        }

        /** A dollar quote such as $$ or $body$, or a lone dollar sign, which is skipped either way. */
        void dollarQuote() {
            next();
            while (!done() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
                next();
            }
            if (!done() && peek() == '$') {
                next();
            }
        }
    }
}
