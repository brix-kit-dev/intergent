package aie.app;

import aie.adapters.storepg.PgRuntime;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.ports.DatabaseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The admin role of blueprint 12.5: opens tenants and applications from the command line, with no HTTP entry. Standard
 * output holds only the new ID, so a script can take it from there.
 */
final class AdminRole {

    static final String USAGE = """
            Usage: --role=admin <command> [options]
              create-tenant [--parent=<tenant id>]
              create-application --tenant=<tenant id> [--environment=test|live] \
            [--data-class=public|internal|personal|sensitive]""";

    private static final String CELL_CODE = "engine.cell.code";
    /** Blueprint 12.1: the cell code goes into API keys, and anything else would break parsing them. */
    private static final Pattern CELL = Pattern.compile("[a-z0-9]{1,8}");
    private static final Set<String> SETTINGS = Set.of("role", RoleSettings.DATABASE_URL, RoleSettings.APP_PASSWORD, CELL_CODE);
    private static final Map<String, Options> COMMANDS = Map.of(
            "create-tenant", new Options(Set.of(), Set.of("parent")),
            "create-application", new Options(Set.of("tenant"), Set.of("environment", "data-class")));

    private AdminRole() {
    }

    static int run(String... args) {
        Command command;
        try {
            command = Command.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return EngineApplication.EXIT_USAGE;
        }
        try {
            RoleSettings settings = RoleSettings.read(args);
            String cell = command.name().equals("create-tenant") ? cellCode(settings) : null;
            Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(
                    settings.required(RoleSettings.DATABASE_URL), settings.required(RoleSettings.APP_PASSWORD)));
            System.out.println(switch (command.name()) {
                case "create-tenant" -> provisioning.createTenant(cell, command.option("parent"));
                case "create-application" -> provisioning.createApplication(
                        command.option("tenant"), command.option("environment", "test"), command.option("data-class", "internal"));
                default -> throw new IllegalStateException("No action for " + command.name());
            });
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Admin failed: " + reason(e));
            return EngineApplication.EXIT_FAILED;
        }
    }

    private static String cellCode(RoleSettings settings) {
        String cell = settings.required(CELL_CODE);
        if (!CELL.matcher(cell).matches()) {
            throw new IllegalArgumentException("ENGINE_CELL_CODE must be 1 to 8 lowercase letters and digits");
        }
        return cell;
    }

    /** The database says best what it refused, such as a value its check does not allow. */
    private static String reason(Exception failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == failure ? failure.getMessage() : failure.getMessage() + ": " + cause.getMessage();
    }

    private record Options(Set<String> required, Set<String> optional) {
    }

    private record Command(String name, Map<String, String> options) {

        static Command parse(String... args) {
            DefaultApplicationArguments arguments = new DefaultApplicationArguments(args);
            List<String> words = arguments.getNonOptionArgs();
            if (words.size() != 1) {
                throw new IllegalArgumentException(words.isEmpty() ? "A command is required." : "Give one command, not " + words + ".");
            }
            String name = words.getFirst();
            Options allowed = COMMANDS.get(name);
            if (allowed == null) {
                throw new IllegalArgumentException("Unknown command: " + name + ".");
            }
            Map<String, String> options = new HashMap<>();
            for (String option : arguments.getOptionNames()) {
                if (SETTINGS.contains(option)) {
                    continue;
                }
                if (!allowed.required().contains(option) && !allowed.optional().contains(option)) {
                    throw new IllegalArgumentException(name + " does not take --" + option + ".");
                }
                List<String> values = arguments.getOptionValues(option);
                if (values.size() != 1 || values.getFirst().isBlank()) {
                    throw new IllegalArgumentException("--" + option + " needs one value.");
                }
                options.put(option, values.getFirst());
            }
            for (String option : allowed.required()) {
                if (!options.containsKey(option)) {
                    throw new IllegalArgumentException(name + " needs --" + option + ".");
                }
            }
            return new Command(name, options);
        }

        String option(String name) {
            return options.get(name);
        }

        String option(String name, String otherwise) {
            return options.getOrDefault(name, otherwise);
        }
    }
}
