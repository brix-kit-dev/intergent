package aie.app;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * Like Spring's OutputCaptureExtension, except that what a test prints stays out of the console. The admin role
 * prints each new key, and the console of a CI run is its log, which anyone who can see the run can read. A failing
 * test still shows what it printed, with the secret part of every key masked.
 */
final class QuietOutput implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    /** API keys and site keys, as ApiKeys and SiteKeys make them. The 40 characters at the end are the secret. */
    static final Pattern KEY = Pattern.compile("(aie_(?:site_)?(?:live|test)_[a-z0-9]{1,8}_)[0-9A-Za-z]{40}");
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(QuietOutput.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put(Capture.class, Capture.start());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        context.getStore(NAMESPACE).remove(Capture.class, Capture.class).stop(context.getExecutionException().isPresent());
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == CapturedOutput.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return context.getStore(NAMESPACE).get(Capture.class, Capture.class);
    }

    /** The text with the secret part of every key replaced by {@code <masked>}. */
    static String masked(String text) {
        return KEY.matcher(text).replaceAll("$1<masked>");
    }

    /** System.out and System.err, taken over from the console until {@link #stop}. */
    static final class Capture implements CapturedOutput {

        private final PrintStream consoleOut = System.out;
        private final PrintStream consoleErr = System.err;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final ByteArrayOutputStream all = new ByteArrayOutputStream();

        private Capture() {
        }

        static Capture start() {
            Capture capture = new Capture();
            System.setOut(capture.into(capture.out));
            System.setErr(capture.into(capture.err));
            return capture;
        }

        /** Gives the console back. After a failure it also gets what was printed, with the keys masked. */
        void stop(boolean failed) {
            System.setOut(consoleOut);
            System.setErr(consoleErr);
            if (failed) {
                consoleOut.print(masked(getAll()));
                consoleOut.flush();
            }
        }

        /** A stream into one buffer and into the buffer of both, so that getAll keeps the order things came in. */
        private PrintStream into(ByteArrayOutputStream own) {
            return new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    synchronized (Capture.this) {
                        own.write(b);
                        all.write(b);
                    }
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    synchronized (Capture.this) {
                        own.write(bytes, offset, length);
                        all.write(bytes, offset, length);
                    }
                }
            }, true, StandardCharsets.UTF_8);
        }

        @Override
        public synchronized String getAll() {
            return all.toString(StandardCharsets.UTF_8);
        }

        @Override
        public synchronized String getOut() {
            return out.toString(StandardCharsets.UTF_8);
        }

        @Override
        public synchronized String getErr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return getAll();
        }
    }
}
