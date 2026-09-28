package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

/** Incomplete receives must not assemble old content; completion must still validate it. */
final class ProjectInboxTest {
    static void run(Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("project-inbox"));
        Config config = new Config(Role.HOST, List.of(new Config.Mapping("repo", ".", ".")),
                List.of(), 4, "git");
        byte[] data = new byte[16000];
        new Random(7).nextBytes(data);
        Payload payload = new Payload(Type.SNAPSHOT, "repo", "a".repeat(40), config.policy(),
                List.of(new Entry("file.bin", "-", hash(data), Encoding.RAW, data)));
        List<String> chunks = ClipboardTransport.split(payload, 4);
        ClipboardTransport.Received first = ClipboardTransport.receive(workspace, config, "repo", chunks.getFirst());
        Path saved = workspace.resolve(".vmsync/inbox").resolve(first.session()).resolve("1.txt");
        Files.writeString(saved, "corrupt persisted block");
        ClipboardTransport.Received second = ClipboardTransport.receive(workspace, config, "repo", chunks.get(1));
        if (second.payload() != null || second.firstMissing() != 3) {
            throw new AssertionError("incomplete session should report missing block without assembling content");
        }
        try {
            for (int i = 2; i < chunks.size(); i++) ClipboardTransport.receive(workspace, config, "repo", chunks.get(i));
            throw new AssertionError("completion accepted corrupt persisted content");
        } catch (IOException expected) {
            // Full validation is deliberately deferred, never skipped.
        }
        Files.writeString(saved, chunks.getFirst());
        ClipboardTransport.Received complete = ClipboardTransport.receive(workspace, config, "repo", chunks.getLast());
        if (complete.firstMissing() != 0 || !Arrays.equals(data, complete.payload().entries().getFirst().data())) {
            throw new AssertionError("resumed session changed binary content");
        }
        System.out.println("PASS ALL project inbox checks");
    }
}
