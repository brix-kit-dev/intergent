package aie.ports;

import java.util.Optional;

/**
 * The secrets the engine is given from outside it (blueprint 9.4, M3-5-3): keys it never keeps in its code, its
 * database or its logs. A secret is named as a setting is, such as {@code engine.audit.master.key}. The first adapter
 * reads the environment; a KMS or Vault can take its place later with no change anywhere else.
 */
public interface SecretPort {

    /**
     * The bytes of the secret with this name, or empty when it is not set. An adapter that cannot read a secret it was
     * given refuses it with the secret's name, never with its value.
     *
     * @throws IllegalArgumentException for a secret that is set but cannot be read, such as one that is not base64
     */
    Optional<byte[]> secret(String name);
}
