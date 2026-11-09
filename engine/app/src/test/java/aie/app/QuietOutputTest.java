package aie.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/** QuietOutput keeps what a test prints, keys included, out of the console, and masks the keys when it shows them. */
class QuietOutputTest {

    /** Stand-ins in the shape of the two key formats. */
    private static final String API_KEY = "aie_test_dev_" + "5".repeat(40);
    private static final String SITE_KEY = "aie_site_live_dev_" + "0123456789".repeat(4);
    private static final String NL = System.lineSeparator();

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private PrintStream realOut;
    private PrintStream realErr;

    /** The console of these tests is a buffer, so that they can see what reached it. */
    @BeforeEach
    void watchTheConsole() {
        realOut = System.out;
        realErr = System.err;
        PrintStream stream = new PrintStream(console, true, UTF_8);
        System.setOut(stream);
        System.setErr(stream);
    }

    @AfterEach
    void restoreTheConsole() {
        System.setOut(realOut);
        System.setErr(realErr);
    }

    @Test
    void whatATestPrintsStaysOutOfTheConsole() {
        QuietOutput.Capture capture = QuietOutput.Capture.start();
        System.out.println("key_1 " + API_KEY);
        System.err.println("trouble");
        System.out.println("site_1 " + SITE_KEY);
        capture.stop(false);

        assertThat(console.toString(UTF_8)).isEmpty();
        assertThat(capture.getOut()).isEqualTo("key_1 " + API_KEY + NL + "site_1 " + SITE_KEY + NL);
        assertThat(capture.getErr()).isEqualTo("trouble" + NL);
        assertThat(capture.getAll()).isEqualTo("key_1 " + API_KEY + NL + "trouble" + NL + "site_1 " + SITE_KEY + NL);
        assertThat(capture).hasToString(capture.getAll());
    }

    @Test
    void theConsoleIsBackAfterwards() {
        QuietOutput.Capture.start().stop(false);
        System.out.print("out");
        System.err.print(" err");

        assertThat(console.toString(UTF_8)).isEqualTo("out err");
    }

    /** A failure is still readable, but no key reaches the log. */
    @Test
    void aFailedTestShowsWhatItPrintedWithTheKeysMasked() {
        QuietOutput.Capture capture = QuietOutput.Capture.start();
        System.out.println("key_1 " + API_KEY);
        System.err.println("site_1 " + SITE_KEY);
        capture.stop(true);

        assertThat(console.toString(UTF_8)).isEqualTo("key_1 aie_test_dev_<masked>" + NL + "site_1 aie_site_live_dev_<masked>" + NL);
    }

    @Test
    void theSecretPartOfEveryKeyIsMasked() {
        String secret = "5".repeat(40);

        assertThat(QuietOutput.masked("key_1 aie_live_jp1_" + secret + " ok")).isEqualTo("key_1 aie_live_jp1_<masked> ok");
        assertThat(QuietOutput.masked("aie_site_test_cell0001_" + secret)).isEqualTo("aie_site_test_cell0001_<masked>");
        assertThat(QuietOutput.masked("x=aie_test_jp1_" + secret + ".")).isEqualTo("x=aie_test_jp1_<masked>.");
        assertThat(QuietOutput.masked("not a key: aie_test_jp1_" + "5".repeat(39))).isEqualTo("not a key: aie_test_jp1_" + "5".repeat(39));
    }

    /** The admin role prints new keys, so its tests use this extension and not Spring's, which passes output on. */
    @Test
    void theAdminRolesTestsUseIt() {
        assertThat(AdminRoleTest.class.getAnnotation(ExtendWith.class).value()).containsExactly(QuietOutput.class);
    }

    @Test
    void theExtensionShowsNothingAfterAPassingTest() {
        QuietOutput extension = new QuietOutput();
        ExtensionContext passed = context(null);
        extension.beforeEach(passed);
        System.out.println("key_1 " + API_KEY);
        extension.afterEach(passed);

        assertThat(console.toString(UTF_8)).isEmpty();
    }

    @Test
    void theExtensionShowsTheMaskedOutputAfterAFailedTest() {
        QuietOutput extension = new QuietOutput();
        ExtensionContext failed = context(new AssertionError("failed"));
        extension.beforeEach(failed);
        System.out.println("key_1 " + API_KEY);
        extension.afterEach(failed);

        assertThat(console.toString(UTF_8)).isEqualTo("key_1 aie_test_dev_<masked>" + NL);
    }

    /** Just enough of a JUnit extension context to run the callbacks here, with the test's failure or null. */
    private static ExtensionContext context(Throwable failure) {
        Map<Object, Object> values = new HashMap<>();
        ExtensionContext.Store store = proxy(ExtensionContext.Store.class, (method, args) -> switch (method.getName()) {
            case "put" -> values.put(args[0], args[1]);
            case "get" -> values.get(args[0]);
            case "remove" -> values.remove(args[0]);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(ExtensionContext.class, (method, args) -> switch (method.getName()) {
            case "getStore" -> store;
            case "getExecutionException" -> Optional.ofNullable(failure);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> answer.apply(method, args)));
    }
}
