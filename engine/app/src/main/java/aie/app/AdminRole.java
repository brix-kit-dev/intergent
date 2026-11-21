package aie.app;

import aie.adapters.storepg.PgRuntime;
import aie.core.metering.api.Budgets;
import aie.core.metering.internal.DatabaseBudgets;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.core.trust.api.Audit;
import aie.core.trust.api.AuditTrail;
import aie.core.trust.internal.DatabaseAuditTrail;
import aie.http.RequestDateTimes;
import aie.ports.DatabaseException;
import aie.ports.DatabasePort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin role of blueprint 12.5: opens tenants and applications, issues their first key, issues and revokes their
 * site keys, reads a tenant's audit (M3-5-2), and sets and reads a tenant's monthly budget (M3-6-2), from the command
 * line, with no HTTP entry. Standard output holds only what was created, revoked, set or read, so a script can take it
 * from there.
 */
final class AdminRole {

    static final String USAGE = """
            Usage: --role=admin <command> [options]
              create-tenant [--parent=<tenant id>]
              create-application --tenant=<tenant id> [--environment=test|live] \
            [--data-class=public|internal|personal|sensitive]
              create-key --tenant=<tenant id> --application=<application id>
              create-site-key --tenant=<tenant id> --application=<application id> --origins=<origin>[,<origin>...]
              revoke-site-key --tenant=<tenant id> --site-key=<site key id>
              audit --tenant=<tenant id> [--from=<RFC 3339 time>] [--to=<RFC 3339 time>]
              set-budget --tenant=<tenant id> --monthly=<US dollars>
              budget --tenant=<tenant id>""";

    private static final String CELL_CODE = "engine.cell.code";
    /** Blueprint 12.1: the cell code goes into API keys, and anything else would break parsing them. */
    private static final Pattern CELL = Pattern.compile("[a-z0-9]{1,8}");
    private static final Set<String> SETTINGS = Set.of(
            "role", RoleSettings.DATABASE_URL, RoleSettings.APP_PASSWORD, CELL_CODE, RegionPacks.SETTING, MonthlyBudgets.DEFAULT);
    /** The commands of a tenant's budget, which tell its month and its limit as the api does. */
    private static final Set<String> BUDGET_COMMANDS = Set.of("set-budget", "budget");
    private static final Map<String, Options> COMMANDS = Map.of(
            "create-tenant", new Options(Set.of(), Set.of("parent")),
            "create-application", new Options(Set.of("tenant"), Set.of("environment", "data-class")),
            "create-key", new Options(Set.of("tenant", "application"), Set.of()),
            "create-site-key", new Options(Set.of("tenant", "application", "origins"), Set.of()),
            "revoke-site-key", new Options(Set.of("tenant", "site-key"), Set.of()),
            "audit", new Options(Set.of("tenant"), Set.of("from", "to")),
            "set-budget", new Options(Set.of("tenant", "monthly"), Set.of()),
            "budget", new Options(Set.of("tenant"), Set.of()));
    /** One line of the audit, as JSON, which a script can read a line at a time. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AdminRole() {
    }

    /**
     * The command line is read whole first: a time of the audit's window that is not RFC 3339, a window that ends before
     * it starts, or a budget that is no amount of US dollars, is a usage error like any other, before the role connects
     * to the database. A budget command reads the region pack and the cell's default monthly budget as the api does,
     * before it connects too, so that it tells the same month and the same limit.
     */
    static int run(String... args) {
        Command command;
        Window window;
        BigDecimal monthly;
        try {
            command = Command.parse(args);
            window = command.name().equals("audit") ? Window.of(command) : null;
            monthly = command.name().equals("set-budget") ? MonthlyBudgets.amount(command.option("monthly"), "--monthly") : null;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return EngineApplication.EXIT_USAGE;
        }
        try {
            RoleSettings settings = RoleSettings.read(args);
            String cell = command.name().equals("create-tenant") ? cellCode(settings) : null;
            ZoneId zone = BUDGET_COMMANDS.contains(command.name())
                    ? RegionPacks.read(settings.required(RegionPacks.SETTING)).policy().timeZone() : null;
            BigDecimal defaultBudget = zone == null ? null : MonthlyBudgets.defaultLimit(settings);
            DatabasePort database = PgRuntime.connect(
                    settings.required(RoleSettings.DATABASE_URL), settings.required(RoleSettings.APP_PASSWORD));
            if (window != null) {
                new DatabaseAuditTrail(database).read(command.option("tenant"), window.from(), window.to(),
                        recorded -> System.out.println(line(recorded)));
                return 0;
            }
            Provisioning provisioning = new DatabaseProvisioning(database);
            if (zone != null) {
                String tenantId = command.option("tenant");
                if (!provisioning.hasTenant(tenantId)) {
                    throw new IllegalArgumentException("Tenant " + tenantId + " does not exist");
                }
                Budgets budgets = new DatabaseBudgets(database, zone, defaultBudget, Clock.systemUTC());
                if (monthly != null) {
                    budgets.set(tenantId, monthly);
                }
                System.out.println(standing(tenantId, budgets.standing(tenantId)));
                return 0;
            }
            System.out.println(switch (command.name()) {
                case "create-tenant" -> provisioning.createTenant(cell, command.option("parent"));
                case "create-application" -> provisioning.createApplication(
                        command.option("tenant"), command.option("environment", "test"), command.option("data-class", "internal"));
                case "create-key" -> issued(provisioning.createKey(command.option("tenant"), command.option("application")));
                case "create-site-key" -> issued(provisioning.createSiteKey(command.option("tenant"), command.option("application"),
                        Arrays.stream(command.option("origins").split(",", -1)).map(String::strip).toList()));
                case "revoke-site-key" -> revoked(provisioning, command.option("tenant"), command.option("site-key"));
                default -> throw new IllegalStateException("No action for " + command.name());
            });
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Admin failed: " + reason(e));
            return EngineApplication.EXIT_FAILED;
        }
    }

    /** One line, the ID and then the key, so a script can read both. This is the only time the key is shown. */
    private static String issued(Provisioning.IssuedKey key) {
        return key.keyId() + " " + key.key();
    }

    /** The ID of the key just revoked. A key the tenant does not have, or one revoked before, is a failure. */
    private static String revoked(Provisioning provisioning, String tenantId, String siteKeyId) {
        if (!provisioning.revokeSiteKey(tenantId, siteKeyId)) {
            throw new IllegalArgumentException("Site key " + siteKeyId + " is not an active key of tenant " + tenantId);
        }
        return siteKeyId;
    }

    /**
     * One entry of the audit as one line of JSON (M3-5-2): its fields always in this order, each by its name in the
     * table, with null for a field it does not have. The time is the database's, in UTC, as RFC 3339. A sensitive entry
     * has the HMAC of its question or its answer and the key's ID last (M3-5-3).
     */
    static String line(AuditTrail.Recorded recorded) {
        Audit.Entry entry = recorded.entry();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("record_id", recorded.recordId());
        line.put("created_at", recorded.createdAt().toString());
        line.put("request_id", entry.requestId());
        line.put("tenant_id", entry.tenantId());
        line.put("application_id", entry.applicationId());
        line.put("end_user_id", entry.endUserId());
        line.put("conversation_id", entry.conversationId());
        line.put("message_id", entry.messageId());
        line.put("task", entry.task());
        line.put("event", entry.event().wireName());
        line.put("data_class", entry.dataClass() == null ? null : entry.dataClass().wireName());
        line.put("model", entry.model());
        line.put("prompt", entry.prompt());
        line.put("rule", entry.rule());
        line.put("question_hmac", recorded.questionHmac());
        line.put("answer_hmac", recorded.answerHmac());
        line.put("key_id", recorded.keyId());
        return JSON.writeValueAsString(line);
    }

    /**
     * Where a tenant stands this month as one line of JSON (M3-6-2): the month of the region's time zone, the limit that
     * holds for the tenant, whether that limit is its own or else the cell's default, and what it spent. The amounts are
     * US dollars, as exact decimals in strings, so that nothing reads them as floating point.
     */
    static String standing(String tenantId, Budgets.Standing standing) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("tenant_id", tenantId);
        line.put("month", standing.month().toString());
        line.put("monthly_limit_usd", standing.limit().toPlainString());
        line.put("own_limit", standing.own());
        line.put("spent_usd", standing.spent().toPlainString());
        return JSON.writeValueAsString(line);
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

    /** The time window of an audit read: from --from on and before --to, either of which may be left out. */
    private record Window(Instant from, Instant to) {

        static Window of(Command command) {
            Instant from = time(command, "from");
            Instant to = time(command, "to");
            if (from != null && to != null && !from.isBefore(to)) {
                throw new IllegalArgumentException("--from must come before --to.");
            }
            return new Window(from, to);
        }

        /** The option's time, read strictly as RFC 3339, as the contract reads a time (point 46), or null without one. */
        private static Instant time(Command command, String option) {
            String value = command.option(option);
            if (value == null) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value, RequestDateTimes.RFC_3339).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("--" + option + " must be an RFC 3339 time, such as 2026-11-21T09:00:00+09:00,"
                        + " not " + value + ".");
            }
        }
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
