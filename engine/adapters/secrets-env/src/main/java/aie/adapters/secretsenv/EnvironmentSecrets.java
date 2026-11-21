package aie.adapters.secretsenv;

import aie.ports.SecretPort;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link SecretPort} on the role's environment (M3-5-3). A secret named like a setting, such as
 * {@code engine.audit.master.key}, is the environment variable {@code ENGINE_AUDIT_MASTER_KEY}, which holds its bytes in
 * base64, the way {@code openssl rand -base64 32} writes them. The role reads its settings the way Spring Boot does, from
 * the environment, so a test can give one on the command line as well.
 */
public final class EnvironmentSecrets implements SecretPort {

    private final Function<String, Optional<String>> settings;

    /** @param settings the role's setting of a name, or empty when it is missing or blank */
    public EnvironmentSecrets(Function<String, Optional<String>> settings) {
        this.settings = Objects.requireNonNull(settings, "Secrets come from the role's settings");
    }

    /** A value that is not base64 is refused by the variable's name; the value itself is never shown. */
    @Override
    public Optional<byte[]> secret(String name) {
        return settings.apply(name).map(value -> {
            try {
                return Base64.getDecoder().decode(value.strip());
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalArgumentException(variable(name) + " is not base64");
            }
        });
    }

    /** The environment variable an operator sets for a secret, such as ENGINE_AUDIT_MASTER_KEY for engine.audit.master.key. */
    public static String variable(String name) {
        return name.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
