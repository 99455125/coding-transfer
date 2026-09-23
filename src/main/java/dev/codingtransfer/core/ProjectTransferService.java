package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static dev.codingtransfer.core.Model.*;

/** Prepares project transfers without depending on IDEA UI or clipboard APIs. */
public final class ProjectTransferService {
    private final Path workspace;
    private final Config config;

    public ProjectTransferService(Path workspace, Config config) {
        this.workspace = workspace;
        this.config = config;
    }

    public record SendResult(SyncEngine.Generated generated, List<String> parts,
                             List<GitHistory.RefChange> refs) {}
    public record ReceiveResult(ClipboardTransport.Received received, SyncEngine.Preview preview) {}

    public SendResult generate(String projectId, boolean incremental) throws IOException {
        SyncEngine.Generated generated;
        BundleSync bundle = null;
        if (config.role() == Role.HOST) {
            generated = new SyncEngine(workspace, config).generate(projectId);
        } else {
            bundle = new BundleSync(workspace, config);
            generated = bundle.generate(projectId, incremental);
        }
        List<String> parts = ClipboardTransport.split(generated.payload(), config.chunkKb());
        List<GitHistory.RefChange> refs = bundle == null ? List.of() : bundle.refChanges(projectId);
        return new SendResult(generated, parts, refs);
    }

    public ReceiveResult receive(String projectId, String text) throws IOException {
        ClipboardTransport.Received received = ClipboardTransport.receive(workspace, config, projectId, text);
        Payload payload = received.payload();
        if (payload != null && payload.type() == Type.ACK) {
            throw new IllegalArgumentException("请在发送页接收回执");
        }
        // Keep completion and preview in the same background operation.
        SyncEngine.Preview preview = payload == null ? null
                : new SyncEngine(workspace, config).preview(projectId, payload);
        return new ReceiveResult(received, preview);
    }
}
