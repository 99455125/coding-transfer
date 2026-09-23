package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.codingtransfer.core.Model.require;

/** Executes fixed local Git commands with isolated IO and disabled hooks/network. */
final class GitCommandRunner {
    private GitCommandRunner() {}

    record Result(int code, String output, byte[] bytes, String error) {}

    static Result command(Path root, String executable, byte[] input, List<String> arguments) throws IOException {
        Path hooks = root.resolve(".vmsync/disabled-hooks");
        SafeFiles.noLinks(hooks);
        Files.createDirectories(hooks);
        try (var files = Files.list(hooks)) {
            require(files.findAny().isEmpty(), "禁用 hooks 的目录必须为空");
        }
        List<String> command = new ArrayList<>(List.of(
                executable, "--no-optional-locks",
                "-c", "core.hooksPath=" + hooks,
                "-c", "core.fsmonitor=false",
                "-c", "core.splitIndex=false",
                "-c", "core.sparseCheckout=false",
                "-c", "gc.auto=0",
                "-c", "maintenance.auto=false",
                "-c", "protocol.allow=never",
                "-C", root.toString()));
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        ExecutorService io = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "vmsync-git");
            thread.setDaemon(true);
            return thread;
        });
        Future<byte[]> stdout = io.submit(() -> {
            try (InputStream stream = process.getInputStream()) { return stream.readAllBytes(); }
        });
        Future<byte[]> stderr = io.submit(() -> {
            try (InputStream stream = process.getErrorStream()) { return stream.readAllBytes(); }
        });
        Future<?> writer = io.submit(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input);
            }
            return null;
        });
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IOException("Git 命令超时: " + arguments.getFirst());
            }
            writer.get(2, TimeUnit.SECONDS);
            byte[] bytes = stdout.get(2, TimeUnit.SECONDS);
            return new Result(process.exitValue(), new String(bytes, StandardCharsets.UTF_8), bytes,
                    new String(stderr.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Git 操作已中断", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Git IO 失败", error);
        } finally {
            process.destroyForcibly();
            stdout.cancel(true);
            stderr.cancel(true);
            writer.cancel(true);
            io.shutdownNow();
        }
    }

    static String run(Path root, String executable, String... arguments) throws IOException {
        return run(root, executable, null, List.of(arguments));
    }

    static String run(Path root, String executable, byte[] input, List<String> arguments) throws IOException {
        Result result = command(root, executable, input, arguments);
        require(result.code() == 0,
                "Git " + arguments.getFirst() + " 失败: " + result.error().strip() + result.output().strip());
        return result.output().strip();
    }
}
