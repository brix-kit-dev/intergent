package aie.app;

import aie.adapters.storepg.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Opens a tenant's audit key from its row, as the superuser reads it, with the tests' master key, and makes an HMAC with
 * it the way the audit promises to (M3-5-3), written out here once more so that the tests hold the format, not the
 * code: the key wrapped by AES-256-GCM, a 12-byte nonce first and the 16-byte tag last, bound to the tenant, a NUL and
 * the key's ID; the HMAC-SHA256 of the label, a NUL and the text, in hex.
 */
final class TenantKeyProbe {

    private TenantKeyProbe() {
    }

    /** The ID of a master key: the first 8 bytes of its SHA-256, in hex. */
    static String masterKeyId(byte[] masterKey) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(masterKey), 0, 8);
    }

    /** The tenant's audit key, opened with the tests' master key. */
    static byte[] key(PostgreSQLContainer database, String tenantId) throws SQLException, GeneralSecurityException {
        List<String> row = TestDatabase.firstColumn(database,
                "SELECT key_id || ' ' || wrapped FROM engine.trust_tenant_keys WHERE tenant_id = '" + tenantId + "'");
        String[] parts = row.getFirst().split(" ");
        byte[] sealed = Base64.getDecoder().decode(parts[1]);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TestApi.AUDIT_MASTER_KEY, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        cipher.updateAAD((tenantId + "\0" + parts[0]).getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(sealed, 12, sealed.length - 12);
    }

    /** The HMAC the audit keeps of a text with this label, question or answer, by the tenant's key. */
    static String hmac(PostgreSQLContainer database, String tenantId, String label, String text)
            throws SQLException, GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key(database, tenantId), "HmacSHA256"));
        mac.update((label + "\0").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
}
