package aie.guard.text;

import static org.assertj.core.api.Assertions.assertThat;

import aie.guard.text.SourceText.Kind;
import aie.guard.text.SourceText.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The words a text rule sees: identifiers and strings with their lines, and nothing from comments. */
class SourceTextTest {

    private static List<String> strings(List<Token> tokens) {
        return tokens.stream().filter(token -> token.kind() == Kind.STRING).map(Token::text).toList();
    }

    private static List<String> identifiers(List<Token> tokens) {
        return tokens.stream().filter(token -> token.kind() == Kind.IDENTIFIER).map(Token::text).toList();
    }

    @Test
    void javaCommentsAreSkipped() {
        List<Token> tokens = SourceText.java("""
                // line comment gpt-4o
                /* block comment claude-3 */
                /** Javadoc such as an order number */
                int kept = 1; // trailing
                """);

        assertThat(identifiers(tokens)).containsExactly("int", "kept");
        assertThat(strings(tokens)).isEmpty();
    }

    @Test
    void javaStringsKeepTheirEscapedQuotes() {
        List<Token> tokens = SourceText.java("String a = \"say \\\"hi\\\" // not a comment\"; char c = '\"'; String b = \"x\";");

        assertThat(strings(tokens)).containsExactly("say \\\"hi\\\" // not a comment", "x");
        assertThat(identifiers(tokens)).containsExactly("String", "a", "char", "c", "String", "b");
    }

    @Test
    void aTextBlockIsOneStringOnTheLineItStarts() {
        List<Token> tokens = SourceText.java("int x;\nString sql = \"\"\"\n    SELECT 1\n    \"\"\";\nint y;");

        assertThat(tokens).contains(new Token(Kind.STRING, "\n    SELECT 1\n    ", 2), new Token(Kind.IDENTIFIER, "y", 5));
    }

    @Test
    void lineNumbersCountEveryLineBreak() {
        List<Token> tokens = SourceText.java("/* one\ntwo */\n\"three\"\n// four\nfive");

        assertThat(tokens).containsExactly(new Token(Kind.STRING, "three", 3), new Token(Kind.IDENTIFIER, "five", 5));
    }

    @Test
    void numbersAreNoIdentifiers() {
        assertThat(identifiers(SourceText.java("long a = 0x1F + 1_000L + 3.14e10;"))).containsExactly("long", "a");
    }

    @Test
    void sqlCommentsAreSkippedAndDoubledQuotesKept() {
        List<Token> tokens = SourceText.sql("""
                -- comment gpt-4o
                /* block */
                INSERT INTO t VALUES ('it''s', "Quoted Name");
                """);

        assertThat(strings(tokens)).containsExactly("it''s");
        assertThat(identifiers(tokens)).containsExactly("INSERT", "INTO", "t", "VALUES", "Quoted Name");
    }

    /** A function body is code, so its words are read like the rest of the script. */
    @Test
    void aDollarQuotedBodyIsReadAsSql() {
        List<Token> tokens = SourceText.sql("CREATE FUNCTION f() RETURNS int AS $body$\nBEGIN RETURN 'x'; -- note\nEND\n$body$;");

        assertThat(identifiers(tokens)).containsExactly("CREATE", "FUNCTION", "f", "RETURNS", "int", "AS", "BEGIN", "RETURN", "END");
        assertThat(tokens).contains(new Token(Kind.STRING, "x", 2));
    }

    @Test
    void anUnclosedStringOrCommentEndsAtTheEnd() {
        assertThat(strings(SourceText.java("String s = \"open"))).containsExactly("open");
        assertThat(SourceText.java("int a; /* open")).hasSize(2);
        assertThat(strings(SourceText.sql("SELECT 'open"))).containsExactly("open");
    }
}
