package aie.app;

/** The only main class. It picks the role and starts it. */
public final class EngineApplication {

    static final int EXIT_USAGE = 2;
    static final int EXIT_NOT_IMPLEMENTED = 3;

    private EngineApplication() {
    }

    public static void main(String[] args) {
        int code = launch(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int launch(String[] args) {
        EngineRole role;
        try {
            role = EngineRole.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return EXIT_USAGE;
        }
        if (role != EngineRole.API) {
            System.err.println("Role " + role.argument() + " is not implemented yet.");
            return EXIT_NOT_IMPLEMENTED;
        }
        ApiRole.start(args);
        return 0;
    }
}
