package dev.codingtransfer.plugin;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.credentialStore.CredentialStore;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.ide.plugins.PluginManagerCore;

import java.util.Base64;
import java.util.UUID;

/** In-memory, value-free diagnostics for correlating the latest pairing save with a failed load. */
final class PairingDiagnostic {
    private static volatile Save latestSave;
    private static volatile String latestText;

    record Save(String id, String scope, String identity, String service, String username, String provider, String version, String build) {}

    private PairingDiagnostic() {}

    static Save beginSave(String scope, String identity, CredentialAttributes attributes, CredentialStore store) {
        Save save = new Save(UUID.randomUUID().toString(), scope, identity, attributes.getServiceName(),
                attributes.getUserName(), providerName(store), pluginVersion(), buildId());
        latestSave = save;
        latestText = format("SAVE_READBACK", save.id(), "pending", save, scope, identity, attributes,
                providerName(store), null, null);
        return save;
    }

    static void finish(String phase, String eventId, String status, Save save, String scope, String identity,
                       CredentialAttributes attributes, CredentialStore store, Credentials credentials, String encoded) {
        Save prior = save != null ? save : latestSave;
        latestText = format(phase, eventId, status, prior, scope, identity, attributes, providerName(store), credentials, encoded);
    }

    static void failed(String phase, String eventId, Save save, String scope, String identity, CredentialAttributes attributes,
                       CredentialStore store, String status) {
        Save prior = save != null ? save : latestSave;
        latestText = format(phase, eventId, status, prior, scope, identity,
                attributes, providerName(store), null, null);
    }

    static String copyText() { return latestText; }
    static boolean currentEvent(String phase, String eventId) {
        String text = latestText;
        return text != null && text.contains("event_id=" + eventId + "\n") && text.contains("phase=" + phase + "\n");
    }
    static void status(String phase, String eventId, String value) {
        String text = latestText;
        if (text != null && currentEvent(phase, eventId)) latestText = text.replace("status=" + statusValue(text) + "\n", "status=" + value + "\n");
    }
    static void clear() { latestText = null; }

    private static String statusValue(String text) {
        for (String line : text.split("\\n")) if (line.startsWith("status=")) return line.substring(7);
        return "unknown";
    }

    private static String format(String phase, String eventId, String status, Save save, String scope, String identity,
                                 CredentialAttributes attrs, String provider, Credentials credentials, String encoded) {
        String passwordState = encoded == null ? "absent" : encoded.isEmpty() ? "empty" : "present";
        int length = encoded == null ? 0 : encoded.length();
        String validation = encoded == null || encoded.isEmpty() ? "not_checked" : "invalid";
        int decodedLength = -1;
        if (encoded != null && !encoded.isEmpty()) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(encoded);
                decodedLength = decoded.length;
                validation = decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)
                        ? "valid_canonical_32" : "invalid_length_or_noncanonical";
            } catch (IllegalArgumentException ignoredException) { validation = "invalid_base64url"; }
        }
        boolean usernameMatches = credentials != null && attrs != null && attrs.getUserName().equals(credentials.getUserName());
        boolean legacyCandidate = !usernameMatches && credentials != null && credentials.getUserName() != null
                && canonicalKey(credentials.getUserName());
        String sameScope = save == null ? "not_observed" : save.scope().equals(scope) ? "same" : "different";
        String sameIdentity = save == null ? "not_observed" : save.identity().equals(identity) ? "same" : "different";
        String sameAttrs = save == null || attrs == null ? "not_observed"
                : save.service().equals(attrs.getServiceName()) && save.username().equals(attrs.getUserName()) ? "same" : "different";
        String id = save == null ? "not_observed" : save.id();
        String version = save == null ? pluginVersion() : save.version();
        String build = save == null ? buildId() : save.build();
        return "Every Sync pairing diagnostics (no secrets)\n"
                + "event_id=" + eventId + "\nphase=" + phase + "\nstatus=" + status + "\n"
                + "latest_save_event=" + id + "\nsame_scope=" + sameScope + "\nsame_identity=" + sameIdentity
                + "\nsame_attributes=" + sameAttrs + "\nprovider=" + provider + "\nplugin_version=" + version
                + "\nide_build=" + build + "\npassword=" + passwordState + "\npassword_length=" + length
                + "\npassword_validation=" + validation + "\ndecoded_length=" + (decodedLength < 0 ? "not_decoded" : decodedLength)
                + "\nusername_matches_attribute=" + usernameMatches + "\nlegacy_candidate=" + legacyCandidate;
    }

    private static boolean canonicalKey(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException e) { return false; }
    }

    private static String providerName(CredentialStore store) {
        if (store instanceof PasswordSafe) {
            try {
                Object provider = store.getClass().getMethod("getCurrentProvider").invoke(store);
                if (provider != null) return provider.getClass().getName();
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return store.getClass().getName();
    }

    private static String pluginVersion() {
        try {
            var descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.shie.tools.git-change-export"));
            return descriptor == null ? "unknown" : descriptor.getVersion();
        } catch (RuntimeException e) { return "unknown"; }
    }

    private static String buildId() {
        try { return PluginManagerCore.getBuildNumber().asString(); }
        catch (RuntimeException e) { return "unknown"; }
    }
}
