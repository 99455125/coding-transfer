package dev.codingtransfer.plugin;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.CredentialStore;
import com.intellij.credentialStore.Credentials;
import com.intellij.credentialStore.keePass.KeePassCredentialStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Credential regression tests backed by temporary KeePass files and narrow provider doubles. */
public final class PairingSecretServiceTest {
    private PairingSecretServiceTest() {}

    public static void main(String[] args) throws Exception {
        actualKeePassBehavior();
        recoverableLegacyProvider();
        malformedAndUnavailableProviders();
        diagnosticCorrelation();
        System.out.println("PASS KeePass persistence, legacy-loss diagnosis, migration, provider failures, and save/send diagnostics");
    }

    private static void actualKeePassBehavior() throws Exception {
        Path workspace = Files.createTempDirectory("pairing-keepass-test-");
        KeePassCredentialStore provider = new KeePassCredentialStore(workspace.resolve("test.kdbx"), workspace.resolve("test.key"));
        PairingSecretService service = new PairingSecretService(workspace, provider);
        byte[] key = key(11);
        CredentialAttributes attrs = attributes(workspace, "test_identity");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);

        // Reproduce the old call against IC-251's actual provider: it replaces username with the attribute
        // and persists no password. The original encoded key is irretrievably lost in this provider.
        provider.set(attrs, new Credentials(encoded));
        Credentials legacyResult = provider.get(attrs);
        check(attrs.getUserName().equals(legacyResult.getUserName()), "KeePass returns attribute username for old write");
        check(legacyResult.getPasswordAsString() == null, "KeePass old write has no password");
        IOException lost = expectIOException(() -> service.load("test_identity"));
        check(lost.getMessage().contains("旧版配对未保存完整密钥"), "old KeePass data has actionable legacy-loss diagnosis");
        provider.set(attrs, new Credentials("pairing-test_identity", ""));
        IOException empty = expectIOException(() -> service.load("test_identity"));
        check(empty.getMessage().contains("旧版配对未保存完整密钥"), "KeePass empty password is classified as absent");

        // Explicit import overwrites the same attributes with the correct two-field Credentials form.
        service.storeAndVerify("test_identity", key);
        PairingSecretService reconstructed = new PairingSecretService(workspace, provider);
        check(java.util.Arrays.equals(key, reconstructed.load("test_identity")), "new KeePass credential survives service reconstruction");

        String normalEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key(29));
        provider.set(attributes(workspace, "normal_identity"), new Credentials("pairing-normal_identity", normalEncoded));
        Credentials normal = provider.get(attributes(workspace, "normal_identity"));
        check(normalEncoded.equals(normal.getPasswordAsString()), "normal new password credential remains in password field");
        check(java.util.Arrays.equals(key(29), reconstructed.load("normal_identity")), "normal new credential is not treated as legacy");
    }

    private static void recoverableLegacyProvider() throws Exception {
        Path workspace = Files.createTempDirectory("pairing-legacy-test-");
        Map<String, Credentials> values = new HashMap<>();
        CredentialStore provider = new CredentialStore() {
            private String id(CredentialAttributes a) { return a.getServiceName() + "\n" + a.getUserName(); }
            @Override public Credentials get(CredentialAttributes a) { return values.get(id(a)); }
            @Override public void set(CredentialAttributes a, Credentials c) { values.put(id(a), c); }
        };
        PairingSecretService service = new PairingSecretService(workspace, provider);
        byte[] key = key(3);
        check(service.load("test_identity") == null, "missing credential remains unconfigured");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        String id = attributes(workspace, "test_identity").getServiceName() + "\npairing-test_identity";
        values.put(id, new Credentials(encoded)); // Provider that truly preserves the old username field.
        check(java.util.Arrays.equals(key, service.load("test_identity")), "strict canonical legacy username is safely migrated");
        Credentials migrated = values.get(id);
        check(encoded.equals(migrated.getPasswordAsString()) && "pairing-test_identity".equals(migrated.getUserName()), "migration writes password and matching username");

        values.put(id, new Credentials("pairing-test_identity", ""));
        IOException empty = expectIOException(() -> service.load("test_identity"));
        check(empty.getMessage().contains("旧版配对未保存完整密钥"), "empty password is treated as absent, not malformed key");
        values.put(id, new Credentials("not-canonical"));
        check(expectIOException(() -> service.load("test_identity")).getMessage().contains("旧版配对未保存完整密钥"), "provider-overridden username is not migrated");
    }

    private static void malformedAndUnavailableProviders() throws Exception {
        Path workspace = Files.createTempDirectory("pairing-provider-test-");
        Map<String, Credentials> values = new HashMap<>();
        CredentialStore provider = new CredentialStore() {
            private String id(CredentialAttributes a) { return a.getServiceName() + "\n" + a.getUserName(); }
            @Override public Credentials get(CredentialAttributes a) { return values.get(id(a)); }
            @Override public void set(CredentialAttributes a, Credentials c) { values.put(id(a), c); }
        };
        PairingSecretService service = new PairingSecretService(workspace, provider);
        String id = attributes(workspace, "test_identity").getServiceName() + "\npairing-test_identity";
        values.put(id, new Credentials("pairing-test_identity", "damaged"));
        check(expectIOException(() -> service.load("test_identity")).getMessage().contains("密码字段损坏"), "malformed stored password is distinguished");

        CredentialStore failing = new CredentialStore() {
            @Override public Credentials get(CredentialAttributes a) { throw new IllegalStateException("unavailable"); }
            @Override public void set(CredentialAttributes a, Credentials c) { throw new IllegalStateException("unavailable"); }
        };
        check(expectIOException(() -> new PairingSecretService(workspace, failing).load("test_identity"))
                .getMessage().contains("无法访问 PasswordSafe"), "provider access error is distinguished");
        check(expectIOException(() -> new PairingSecretService(workspace, failing).store("test_identity", key(1)))
                .getMessage().contains("无法将配对密钥保存"), "provider write error is surfaced");
    }

    private static void diagnosticCorrelation() throws Exception {
        Path workspace = Files.createTempDirectory("pairing-diagnostic-test-");
        byte[] key = key(41);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        FlappingProvider missingLater = new FlappingProvider(encoded, "missing");
        PairingSecretService service = new PairingSecretService(workspace, missingLater);
        service.storeAndVerify("test_identity", key);
        check(service.loadForSend("test_identity") == null, "provider may report immediate save and later missing value");
        String missingReport = PairingDiagnostic.copyText();
        check(missingReport.contains("phase=SEND_LOAD") && missingReport.contains("same_scope=same")
                        && missingReport.contains("same_identity=same") && missingReport.contains("same_attributes=same")
                        && missingReport.contains("password=absent") && !missingReport.contains(encoded)
                        && !missingReport.contains(workspace.toString()),
                "missing-after-save report correlates safe metadata without values");

        FlappingProvider damagedLater = new FlappingProvider(encoded, "damaged");
        PairingSecretService damagedService = new PairingSecretService(workspace, damagedLater);
        damagedService.storeAndVerify("test_identity", key);
        expectIOException(() -> damagedService.loadForSend("test_identity"));
        String damagedReport = PairingDiagnostic.copyText();
        check(damagedReport.contains("status=password_corrupt") && damagedReport.contains("password_validation=invalid_"),
                "corrupt-after-save report identifies field state");

        Path otherWorkspace = Files.createTempDirectory("pairing-other-scope-");
        PairingSecretService other = new PairingSecretService(otherWorkspace, missingLater);
        other.loadForSend("test_identity");
        check(PairingDiagnostic.copyText().contains("same_scope=different"), "send from another workspace is distinguished");
    }

    private static final class FlappingProvider implements CredentialStore {
        private Credentials saved;
        private final String afterSave;
        private final String encoded;
        private int readsAfterWrite;
        private String attributesUser;
        FlappingProvider(String encoded, String afterSave) { this.encoded = encoded; this.afterSave = afterSave; }
        @Override public Credentials get(CredentialAttributes a) {
            attributesUser = a.getUserName();
            readsAfterWrite++;
            if (readsAfterWrite == 1) return saved;
            return switch (afterSave) {
                case "missing" -> null;
                case "damaged" -> new Credentials(attributesUser, "damaged");
                default -> saved;
            };
        }
        @Override public void set(CredentialAttributes a, Credentials c) { attributesUser = a.getUserName(); saved = c; readsAfterWrite = 0; }
    }

    private static CredentialAttributes attributes(Path workspace, String identity) throws Exception {
        String workspaceId = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(workspace.toRealPath().toString().getBytes(StandardCharsets.UTF_8)));
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("Every Sync", workspaceId), "pairing-" + identity);
    }

    private static byte[] key(int seed) { byte[] result = new byte[32]; for (int i = 0; i < result.length; i++) result[i] = (byte) (i * 7 + seed); return result; }
    private static IOException expectIOException(Throwing action) throws Exception {
        try { action.run(); } catch (IOException e) { return e; }
        throw new AssertionError("expected IOException");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
