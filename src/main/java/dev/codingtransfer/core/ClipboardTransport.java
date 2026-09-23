package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import dev.codingtransfer.core.Model.Payload;

/** Stable project transport entry point; framing and disk sessions are separate. */
public final class ClipboardTransport {
    private ClipboardTransport() {}

    public record Received(String session, int received, int total, int firstMissing, Payload payload) {}

    public static List<String> split(Payload payload, int chunkKb) throws IOException {
        return ProjectChunkCodec.split(payload, chunkKb);
    }

    public static Received receive(Path workspace, Config config, String selectedId, String text) throws IOException {
        return ProjectInbox.receive(workspace, config, selectedId, text);
    }

    public static void clear(Path workspace, String session) throws IOException {
        ProjectInbox.clear(workspace, session);
    }
}
