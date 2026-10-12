package dev.codingtransfer.core;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Explicit suites; never silently expands an omitted/unknown group into a full run. */
public final class TestRunner {
    private static final List<String> GROUPS = List.of("core", "worktree", "bundle", "transport", "cache", "large");

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!args[0].equals("all") && !GROUPS.contains(args[0]))) {
            throw new IllegalArgumentException("Choose test group: " + GROUPS + " or all");
        }
        for (String group : args[0].equals("all") ? GROUPS : List.of(args[0])) run(group);
    }

    private static void run(String group) throws Exception {
        Path fixture = Files.createTempDirectory("every-sync-" + group + "-").toRealPath();
        Path logs = Path.of(System.getProperty("everysync.test.logs", ".build/test-logs"));
        Files.createDirectories(logs);
        Path log = logs.resolve(group + ".log");
        PrintStream console = System.out;
        Throwable failure = null;
        try (PrintStream detail = new PrintStream(Files.newOutputStream(log), true, StandardCharsets.UTF_8)) {
            System.setOut(detail);
            try {
                switch (group) {
                    case "core" -> CoreTest.run("core", fixture);
                    case "worktree" -> { WorktreeTest.run(fixture); UntrackedTest.run(fixture); }
                    case "bundle" -> { BundleSessionExitTest.run(fixture); BundleTest.run(fixture); RefsTest.run(fixture); TransferCompletionIntegrationTest.run(fixture); }
                    case "transport" -> { EncryptedFrameTest.run(); TransferSessionStoreTest.run(fixture); TransferCompletionStoreTest.run(fixture); TransferSessionLeaseTest.run(); ReliableTransferTest.run(fixture); CoreTest.run("transport", fixture); FileTransferTest.run(fixture); ProjectInboxTest.run(fixture); }
                    case "cache" -> SendCacheTest.run(fixture);
                    case "large" -> FileTransferTest.runLarge(fixture);
                    default -> throw new IllegalArgumentException(group);
                }
            } catch (Throwable error) {
                failure = error;
                error.printStackTrace(detail);
            } finally {
                System.setOut(console);
            }
        }
        if (failure != null) {
            console.println("FAIL " + group + " · log: " + log + " · fixture: " + fixture);
            List<String> lines = Files.readAllLines(log);
            lines.subList(Math.max(0, lines.size()-60), lines.size()).forEach(console::println);
            throw new AssertionError("Test group failed: " + group, failure);
        }
        TestSupport.deleteFixture(fixture);
        console.println("PASS " + group);
    }
}
