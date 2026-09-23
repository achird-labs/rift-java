package io.github.achirdlabs.rift.spawn;

import io.github.achirdlabs.rift.SpawnOptions;
import io.github.achirdlabs.rift.UpstreamTrust;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.transport.HostAuthority;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A launched, health-checked {@code rift} engine process: manages its lifecycle from {@code start}
 * through a bounded startup health-poll to a graceful (then forced) shutdown, and guards against
 * orphaning it if the JVM exits first.
 */
public final class RiftProcess {

    private static final Logger LOG = System.getLogger(RiftProcess.class.getName());
    private static final int LOG_TAIL_LINES = 50;
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration HEALTH_REQUEST_TIMEOUT = Duration.ofMillis(500);

    private final Process process;
    private final URI adminUri;
    private final Deque<String> logTail;
    private final Thread shutdownHook;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private RiftProcess(Process process, URI adminUri, Deque<String> logTail, Thread shutdownHook) {
        this.process = process;
        this.adminUri = adminUri;
        this.logTail = logTail;
        this.shutdownHook = shutdownHook;
    }

    public static RiftProcess launch(Path binary, SpawnOptions opts) {
        int port = choosePort(opts);
        URI adminUri = HostAuthority.httpUri(opts.host(), port);
        Path pidFile = createPidFile();
        List<String> command = buildCommand(binary, opts, port, pidFile);
        skipVerifyWarning(opts).ifPresent(warning -> LOG.log(Level.WARNING, warning));

        ProcessBuilder builder = new ProcessBuilder(command);
        opts.workingDir().ifPresent(dir -> builder.directory(dir.toFile()));
        builder.environment().putAll(opts.env());

        Deque<String> logTail = new ArrayDeque<>();
        Process process;
        try {
            if (opts.inheritLog()) {
                builder.inheritIO();
                process = builder.start();
            } else {
                builder.redirectErrorStream(true);
                process = builder.start();
                drainAsync(process.getInputStream(), logTail);
            }
        } catch (IOException e) {
            deleteQuietly(pidFile);
            throw new EngineUnavailable("cannot launch rift binary " + binary + ": " + e.getMessage(), e);
        }

        Thread hook = new Thread(() -> killTree(process), "rift-process-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);

        try {
            awaitHealthy(process, adminUri, opts.startupTimeout(), logTail);
        } catch (RuntimeException e) {
            removeShutdownHook(hook);
            process.destroyForcibly();
            throw e;
        } finally {
            deleteQuietly(pidFile);
        }

        return new RiftProcess(process, adminUri, logTail, hook);
    }

    public URI adminUri() {
        return adminUri;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Stops the process: SIGTERM, wait up to {@code shutdownTimeout}, then force-kill. Idempotent. */
    public void stop(Duration shutdownTimeout) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        removeShutdownHook(shutdownHook);
        if (!process.isAlive()) {
            return;
        }
        // snapshot the tree before SIGTERM: once the parent exits, descendants() is empty/reparented
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        try {
            if (!process.waitFor(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        // reap any children the engine did not clean up itself, on both the graceful and forced paths
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    /** Force-kills the process and every descendant — used by the JVM shutdown hook (abrupt exit). */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down; the hook itself will run (or is running).
        }
    }

    private static int choosePort(SpawnOptions opts) {
        if (opts.adminPort() > 0) {
            return opts.adminPort();
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new EngineUnavailable("cannot allocate an ephemeral admin port: " + e.getMessage(), e);
        }
    }

    private static Path createPidFile() {
        try {
            return Files.createTempFile("rift-java-", ".pid");
        } catch (IOException e) {
            throw new EngineUnavailable("cannot create a pidfile for the rift process: " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup of a transient temp file; nothing depends on this succeeding.
        }
    }

    /**
     * The warning to log when {@code opts} turns upstream verification off. The engine logs one too,
     * but into the output this SDK drains and only surfaces when the process fails to start.
     */
    static Optional<String> skipVerifyWarning(SpawnOptions opts) {
        if (!(opts.upstreamTrust().orElse(null) instanceof UpstreamTrust.SkipVerify)) {
            return Optional.empty();
        }
        return Optional.of("spawning rift with --upstream-tls-skip-verify: proxy stubs accept any upstream "
                + "certificate, so a recording can capture a man-in-the-middle's traffic. "
                + "Development only; prefer UpstreamTrust.CaFile.");
    }

    // Package-private (not private) so RiftProcessTest can pin the argument order: the rift CLI
    // rejects the admin options unless they precede the `start` subcommand.
    static List<String> buildCommand(Path binary, SpawnOptions opts, int port, Path pidFile) {
        // The rift CLI takes its admin-API options as GLOBAL options that must precede the
        // subcommand (`rift [OPTIONS] start`); clap rejects them when placed after `start`. So every
        // option is added first and the `start` subcommand goes last.
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toString());
        cmd.add("--port");
        cmd.add(Integer.toString(port));
        cmd.add("--host");
        cmd.add(opts.host());
        if (opts.allowInjection()) {
            cmd.add("--allow-injection");
        }
        if (opts.localOnly()) {
            cmd.add("--local-only");
        }
        cmd.add("--loglevel");
        cmd.add(opts.logLevel());
        cmd.add("--nologfile");
        cmd.add("--pidfile");
        cmd.add(pidFile.toString());
        // SpawnOptions.build() has already rejected CaPem (the CLI has no inline form).
        opts.upstreamTrust().ifPresent(trust -> {
            if (trust instanceof UpstreamTrust.CaFile file) {
                cmd.add("--upstream-ca-file");
                cmd.add(file.pem().toString());
            } else if (trust instanceof UpstreamTrust.SkipVerify) {
                cmd.add("--upstream-tls-skip-verify");
            }
        });
        cmd.add("start");
        return cmd;
    }

    private static void drainAsync(InputStream in, Deque<String> tail) {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (tail) {
                        tail.addLast(line);
                        while (tail.size() > LOG_TAIL_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // The stream closed because the process exited; nothing more to drain.
            }
        }, "rift-process-log-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private static void awaitHealthy(Process process, URI adminUri, Duration startupTimeout, Deque<String> logTail) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HEALTH_REQUEST_TIMEOUT).build();
        URI healthUri = adminUri.resolve("/imposters");
        Instant deadline = Instant.now().plus(startupTimeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new EngineUnavailable("rift process exited with code " + process.exitValue()
                        + " during startup" + tailMessage(logTail));
            }
            if (poll(client, healthUri)) {
                return;
            }
            sleep(HEALTH_POLL_INTERVAL);
        }
        throw new EngineUnavailable("rift process did not become healthy within " + startupTimeout
                + tailMessage(logTail));
    }

    private static boolean poll(HttpClient client, URI healthUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri).timeout(HEALTH_REQUEST_TIMEOUT).GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnavailable("interrupted while waiting for the rift process to become healthy");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnavailable("interrupted while waiting for the rift process to become healthy");
        }
    }

    private static String tailMessage(Deque<String> logTail) {
        synchronized (logTail) {
            if (logTail.isEmpty()) {
                return "";
            }
            return "\n--- rift process output (last " + logTail.size() + " lines) ---\n" + String.join("\n", logTail);
        }
    }
}
