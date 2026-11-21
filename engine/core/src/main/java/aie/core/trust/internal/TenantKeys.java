package aie.core.trust.internal;

import aie.core.Ids;
import aie.ports.Tx;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The tenants' audit keys (blueprint 9.4, M3-5 decision 1). Each tenant has one random 256-bit key, made at its first
 * sensitive entry and kept in {@code trust_tenant_keys} wrapped by the master key with AES-256-GCM, so the database never
 * holds a key it could use by itself. The wrapping is bound to the tenant and the key's ID, so a wrapped key moved to
 * another tenant's row does not open. Deleting a tenant's row destroys its key: its HMACs can never be checked again.
 * No key is kept in memory past the transaction that used it, so a destroyed key is gone at once.
 */
final class TenantKeys {

    /** The master key's length: AES-256. */
    static final int MASTER_KEY_BYTES = 32;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String FIND = "SELECT key_id, master_key_id, wrapped FROM trust_tenant_keys WHERE tenant_id = ?";
    private static final String ADD = """
            INSERT INTO trust_tenant_keys (key_id, tenant_id, master_key_id, wrapped) VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id) DO NOTHING
            """;

    private final SecretKeySpec master;
    private final String masterKeyId;

    /** @throws IllegalArgumentException for a master key that is not 32 bytes */
    TenantKeys(byte[] masterKey) {
        if (masterKey == null || masterKey.length != MASTER_KEY_BYTES) {
            throw new IllegalArgumentException("The audit's master key holds " + MASTER_KEY_BYTES + " bytes");
        }
        this.master = new SecretKeySpec(masterKey.clone(), "AES");
        this.masterKeyId = idOf(masterKey);
    }

    /** A master key's ID: the first 8 bytes of its SHA-256, in hex, as a session key's {@code kid} is made. */
    static String idOf(byte[] masterKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(masterKey), 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java has no SHA-256", e);
        }
    }

    /**
     * The tenant's key, made first when it has none, in the caller's tenant transaction. Two transactions that make a
     * tenant's first key at once keep one: the second's insert waits for the first and then does nothing, and both read
     * the first's key.
     *
     * @throws IllegalStateException for a key this master key did not wrap, or one that does not open with it
     */
    Key of(Tx tx, String tenantId) {
        List<Key> found = tx.query(FIND, row -> open(tenantId, row.string("key_id"), row.string("master_key_id"),
                row.string("wrapped")), tenantId);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        String keyId = Ids.next("tk");
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        tx.update(ADD, keyId, tenantId, masterKeyId, wrap(tenantId, keyId, key));
        Arrays.fill(key, (byte) 0);
        return tx.query(FIND, row -> open(tenantId, row.string("key_id"), row.string("master_key_id"), row.string("wrapped")),
                tenantId).getFirst();
    }

    /** The nonce, then the key encrypted, then the tag, in base64: 80 characters. */
    private String wrap(String tenantId, String keyId, byte[] key) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(boundTo(tenantId, keyId));
            byte[] sealed = cipher.doFinal(key);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The audit key of tenant " + tenantId + " could not be wrapped", e);
        }
    }

    private Key open(String tenantId, String keyId, String wrappedBy, String wrapped) {
        if (!masterKeyId.equals(wrappedBy)) {
            throw new IllegalStateException("The audit key " + keyId + " of tenant " + tenantId + " was wrapped by the master key "
                    + wrappedBy + ", not by this one, " + masterKeyId);
        }
        byte[] sealed = Base64.getDecoder().decode(wrapped);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
            cipher.updateAAD(boundTo(tenantId, keyId));
            return new Key(keyId, cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The audit key " + keyId + " of tenant " + tenantId + " does not open with the master key", e);
        }
    }

    /** What a wrapped key is bound to: its tenant and its ID. */
    private static byte[] boundTo(String tenantId, String keyId) {
        return (tenantId + '\0' + keyId).getBytes(StandardCharsets.UTF_8);
    }

    /** A tenant's audit key, opened for one transaction. */
    static final class Key {

        private final String keyId;
        private final SecretKeySpec key;

        private Key(String keyId, byte[] key) {
            this.keyId = keyId;
            this.key = new SecretKeySpec(key, "HmacSHA256");
            Arrays.fill(key, (byte) 0);
        }

        String keyId() {
            return keyId;
        }

        /**
         * The HMAC-SHA256 of a text, in hex: of its label, a NUL and then the text, all in UTF-8. The label keeps the HMACs
         * of a question and an answer apart, so the two never match each other.
         */
        String hmac(String label, String text) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(key);
                mac.update(label.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) 0);
                return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("This Java has no HMAC-SHA256", e);
            }
        }

        /** Never the key itself. */
        @Override
        public String toString() {
            return "Key[" + keyId + "]";
        }
    }
}
