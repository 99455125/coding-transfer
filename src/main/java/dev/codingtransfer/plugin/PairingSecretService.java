package dev.codingtransfer.plugin;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.credentialStore.CredentialStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Keeps shared AES keys in IDEA PasswordSafe and provides explicit user-driven pairing exchange. */
final class PairingSecretService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String workspaceId;
    private final CredentialStore store;

    record Pairing(String identity, byte[] key) {
        Pairing { key = key.clone(); }
        @Override public byte[] key() { return key.clone(); }
    }

    PairingSecretService(Path workspace) throws IOException {
        this(workspace, PasswordSafe.getInstance());
    }

    PairingSecretService(Path workspace, CredentialStore store) throws IOException {
        this.store = store;
        try {
            workspaceId = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(workspace.toRealPath().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IOException("无法读取本地配对存储标识", e); }
    }

    byte[] load(String identity) throws IOException {
        return load(identity, "LOAD", null, null);
    }

    byte[] loadForSend(String identity) throws IOException {
        return load(identity, "SEND_LOAD", null, java.util.UUID.randomUUID().toString());
    }

    void storeAndVerify(String identity, byte[] key) throws IOException {
        CredentialAttributes attributes = attributes(identity);
        PairingDiagnostic.Save event = PairingDiagnostic.beginSave(workspaceId, identity, attributes, store);
        try {
            store(identity, key);
        } catch (IOException e) {
            PairingDiagnostic.failed("SAVE_READBACK", event.id(), event, workspaceId, identity, attributes, store, "write_failed");
            throw e;
        }
        byte[] saved = load(identity, "SAVE_READBACK", event, event.id());
        if (saved == null || !MessageDigest.isEqual(key, saved)) {
            PairingDiagnostic.status("SAVE_READBACK", event.id(), "readback_mismatch");
            throw new IOException("PasswordSafe 回读密钥与刚导入的配对不一致，设置未保存；请检查 IDE PasswordSafe 状态后重试");
        }
    }

    private byte[] load(String identity, String phase, PairingDiagnostic.Save save, String eventId) throws IOException {
        CredentialAttributes attributes = null;
        if (eventId == null && "SEND_LOAD".equals(phase)) eventId = java.util.UUID.randomUUID().toString();
        try {
            if (identity == null || identity.isBlank()) {
                if (eventId != null) PairingDiagnostic.failed(phase, eventId, save, workspaceId, identity, null, store, "identity_unconfigured");
                throw new IOException("尚未配置传输识别串；请在设置中导入配对配置并保存");
            }
            attributes = attributes(identity);
            Credentials credentials = store.get(attributes);
            if (credentials == null) {
                if (eventId != null) PairingDiagnostic.finish(phase, eventId, "credential_missing", save, workspaceId, identity, attributes, store, null, null);
                return null;
            }
            String encoded = credentials.getPasswordAsString(); // Read the provider secret exactly once per Credentials object.
            if (encoded == null || encoded.isEmpty()) {
                // Older builds accidentally used Credentials(String), which stores this value as userName.
                String legacyValue = credentials.getUserName();
                if (legacyValue == null || legacyValue.equals(attributes.getUserName())) {
                    if (eventId != null) PairingDiagnostic.finish(phase, eventId, "legacy_key_missing", save, workspaceId, identity, attributes, store, credentials, encoded);
                    throw legacyPairingMissing();
                }
                byte[] recovered;
                try { recovered = decodeKey(legacyValue); }
                catch (IOException invalidLegacy) {
                    if (eventId != null) PairingDiagnostic.finish(phase, eventId, "legacy_key_missing", save, workspaceId, identity, attributes, store, credentials, encoded);
                    throw legacyPairingMissing();
                }
                store(identity, recovered);
                Credentials migrated = store.get(attributes);
                String migratedEncoded = migrated == null ? null : migrated.getPasswordAsString();
                if (migrated == null || migratedEncoded == null || migratedEncoded.isEmpty()
                        || !MessageDigest.isEqual(recovered, decodeKey(migratedEncoded)))
                    throw new IOException("无法恢复旧版 PasswordSafe 配对密钥，请重新导入配对配置");
                if (eventId != null) PairingDiagnostic.finish(phase, eventId, "legacy_migrated", save, workspaceId, identity, attributes, store, migrated, migratedEncoded);
                return recovered;
            }
            try {
                byte[] value = decodeKey(encoded);
                if (eventId != null) PairingDiagnostic.finish(phase, eventId, "ok", save, workspaceId, identity, attributes, store, credentials, encoded);
                return value;
            } catch (IOException invalidPassword) {
                if (eventId != null) PairingDiagnostic.finish(phase, eventId, "password_corrupt", save, workspaceId, identity, attributes, store, credentials, encoded);
                throw new IOException("PasswordSafe 中已保存的配对密码字段损坏；请从仍有效的一端复制当前配对并导入，或重新导入原配对配置", invalidPassword);
            }
        } catch (IOException e) {
            if (eventId != null && !PairingDiagnostic.currentEvent(phase, eventId))
                PairingDiagnostic.failed(phase, eventId, save, workspaceId, identity, attributes, store, "configuration_or_provider_error");
            throw e;
        } catch (RuntimeException e) {
            if (eventId != null) PairingDiagnostic.failed(phase, eventId, save, workspaceId, identity, attributes, store, "provider_inaccessible");
            throw new IOException("无法访问 PasswordSafe 配对存储；请检查 IDE PasswordSafe 状态后重试", e);
        }
    }

    void store(String identity, byte[] key) throws IOException {
        if (identity == null || !identity.matches("[A-Za-z0-9_-]{1,128}") || key == null || key.length != 32)
            throw new IOException("配对识别串或密钥格式无效");
        try {
            store.set(attributes(identity), new Credentials("pairing-" + identity,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(key)));
        } catch (RuntimeException e) { throw new IOException("无法将配对密钥保存到 PasswordSafe", e); }
    }

    private static byte[] decodeKey(String encoded) throws IOException {
        if (encoded == null) return null;
        try {
            byte[] value = Base64.getUrlDecoder().decode(encoded);
            if (value.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(value).equals(encoded))
                throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException e) { throw new IOException("密钥字段不是 canonical 32-byte base64url", e); }
    }

    private static IOException legacyPairingMissing() {
        return new IOException("旧版配对未保存完整密钥，无法从 PasswordSafe 恢复；请从仍有效的一端复制当前配对并在此端导入保存。若两端都没有有效密钥且没有原配对配置，请手动生成一次新配对并导入两端");
    }

    Pairing generate() {
        byte[] id = new byte[16], key = new byte[32]; RANDOM.nextBytes(id); RANDOM.nextBytes(key);
        return new Pairing(Base64.getUrlEncoder().withoutPadding().encodeToString(id), key);
    }

    String exportPairing(String identity, byte[] key) throws IOException {
        if (identity == null || !identity.matches("[A-Za-z0-9_-]{1,128}") || key == null || key.length != 32)
            throw new IOException("配对识别串或密钥格式无效");
        return "EVERY-SYNC-PAIR/1\nIDENTITY:" + identity + "\nKEY:" + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    Pairing parsePairing(String value) throws IOException {
        if (value == null || value.length() > 512) throw new IOException("配对配置为空或过大");
        String[] lines = value.strip().split("\\R");
        if (lines.length != 3 || !lines[0].equals("EVERY-SYNC-PAIR/1") || !lines[1].startsWith("IDENTITY:") || !lines[2].startsWith("KEY:"))
            throw new IOException("配对配置格式无效");
        String identity = lines[1].substring(9), encoded = lines[2].substring(4);
        if (!identity.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("配对识别串格式无效");
        try {
            byte[] key = Base64.getUrlDecoder().decode(encoded);
            if (key.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(encoded)) throw new IllegalArgumentException();
            return new Pairing(identity, key);
        } catch (IllegalArgumentException e) { throw new IOException("配对密钥格式无效", e); }
    }

    private CredentialAttributes attributes(String identity) throws IOException {
        if (identity == null || !identity.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("请先设置传输识别串");
        String service = CredentialAttributesKt.generateServiceName("Every Sync", workspaceId);
        return new CredentialAttributes(service, "pairing-" + identity);
    }
}
