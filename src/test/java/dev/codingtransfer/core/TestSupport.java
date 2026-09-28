package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.Role;

/** Helpers for disposable test fixtures only; never included in the plugin JAR. */
final class TestSupport {
    private TestSupport() {}

    static String git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) throw new IOException(output);
        return output;
    }

    static void initRepository(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init", "-q", "-b", "main");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "core.autocrlf", "false");
        git(root, "config", "core.safecrlf", "false");
    }

    static Config config(Path root, Role role) {
        return new Config(role, List.of(new Config.Mapping("repo", root.toString(), root.toString())),
                List.of("excluded/**"), 4, "git");
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    static void deleteFixture(Path fixture) throws IOException {
        // Caller supplies only a newly created test directory; never a repository/workspace root.
        try (var paths = Files.walk(fixture)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
