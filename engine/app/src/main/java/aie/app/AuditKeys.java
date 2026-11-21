package aie.app;

import aie.adapters.secretsenv.EnvironmentSecrets;
import aie.ports.SecretPort;

/**
 * The audit's master key (M3-5-3), which wraps each tenant's audit key: ENGINE_AUDIT_MASTER_KEY, 32 random bytes in
 * base64, the way {@code openssl rand -base64 32} writes them, read through {@link SecretPort}. The api refuses to
 * start without it, since it keeps the HMACs of every sensitive call. A refusal names the variable and never shows its
 * value, because the message goes to the log.
 */
final class AuditKeys {

    /** The master key's setting, which an operator sets as ENGINE_AUDIT_MASTER_KEY. */
    static final String MASTER_KEY = "engine.audit.master.key";
    /** AES-256: the master key wraps the tenants' keys with it. */
    static final int MASTER_KEY_BYTES = 32;

    private AuditKeys() {
    }

    /** @throws IllegalArgumentException when the key is missing, not base64, or not 32 bytes */
    static byte[] masterKey(SecretPort secrets) {
        String variable = EnvironmentSecrets.variable(MASTER_KEY);
        byte[] key = secrets.secret(MASTER_KEY).orElseThrow(() -> new IllegalArgumentException(variable + " is required. "
                + SessionKeys.HOW_TO_MAKE_ONE));
        if (key.length != MASTER_KEY_BYTES) {
            throw new IllegalArgumentException(variable + " must hold " + MASTER_KEY_BYTES + " bytes, and this one holds "
                    + key.length + ". " + SessionKeys.HOW_TO_MAKE_ONE);
        }
        return key;
    }
}
