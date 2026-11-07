package aie.adapters.storepg;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** A throwaway database set up the way a cell is: same image as the dev database, same role script. */
public final class TestDatabase {

    public static final String OWNER_PASSWORD = "owner-test-password";
    public static final String APP_PASSWORD = "app-test-password";

    private static final Pattern IMAGE = Pattern.compile("(?m)^\\s*image:\\s*(\\S+)");
    static final String LOCALHOST = "127.0.0.1";

    private static final String SCRIPT_IN_CONTAINER = "/tmp/create-roles.sql";

    private TestDatabase() {
    }

    public static PostgreSQLContainer container() {
        return new PostgreSQLContainer(DockerImageName.parse(devImage()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("engine")
                .withUsername("postgres")
                .withCopyFileToContainer(MountableFile.forHostPath(repository().resolve("deploy/db/create-roles.sql")), SCRIPT_IN_CONTAINER)
                .withCreateContainerCmdModifier(TestDatabase::listenOnLocalhostOnly);
    }

    /** By default Docker publishes on every address, and this server has a public one (blueprint 12.6). */
    private static void listenOnLocalhostOnly(CreateContainerCmd command) {
        command.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIp(LOCALHOST), ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT)));
    }

    public static ExecResult createRoles(PostgreSQLContainer database, String... variables) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "engine"));
        for (String variable : variables) {
            command.add("-v");
            command.add(variable);
        }
        command.add("-f");
        command.add(SCRIPT_IN_CONTAINER);
        return database.execInContainer(command.toArray(String[]::new));
    }

    public static Connection connect(PostgreSQLContainer database, String user, String password) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), user, password);
    }

    public static Connection admin(PostgreSQLContainer database) throws SQLException {
        return connect(database, database.getUsername(), database.getPassword());
    }

    /** Runs SQL as the superuser, which row-level security does not stop, so it shows what is really in a table. */
    public static void asAdmin(PostgreSQLContainer database, String sql) throws SQLException {
        try (Connection admin = admin(database); Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Reads the first column as the superuser. */
    public static List<String> firstColumn(PostgreSQLContainer database, String sql) throws SQLException {
        try (Connection admin = admin(database);
                Statement statement = admin.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static String devImage() {
        try {
            Matcher matcher = IMAGE.matcher(Files.readString(repository().resolve("deploy/dev/compose.yaml")));
            if (!matcher.find()) {
                throw new IllegalStateException("deploy/dev/compose.yaml names no image");
            }
            return matcher.group(1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path repository() {
        try {
            Path testClasses = Path.of(TestDatabase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return testClasses.getParent().getParent().getParent().getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
