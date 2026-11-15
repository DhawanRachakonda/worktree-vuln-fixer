package dev.subscriber.lifecycle;

import dev.subscriber.runner.RunningSession;
import dev.subscriber.runner.SessionLauncher;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A launcher whose sessions end when the test says so, and which can be told to fail for chosen sessions. */
class FakeLauncher implements SessionLauncher {

    final List<String> launched = new CopyOnWriteArrayList<>();
    final Map<String, CompletableFuture<Void>> ends = new ConcurrentHashMap<>();
    final Set<String> failing = ConcurrentHashMap.newKeySet();
    final Map<String, Path> repositories = new ConcurrentHashMap<>();
    /** What each launched session was started with, by session id. */
    final Map<String, String> agents = new ConcurrentHashMap<>();

    @Override
    public RunningSession launch(String sessionId, Path repository, String agent) throws IOException {
        if (failing.contains(sessionId)) {
            throw new IOException("no desktop to open a window on");
        }
        CompletableFuture<Void> end = new CompletableFuture<>();
        ends.put(sessionId, end);
        agents.put(sessionId, agent);
        repositories.put(sessionId, repository);
        launched.add(sessionId);
        long pid = 1000 + launched.size();
        return new RunningSession() {
            @Override
            public long pid() {
                return pid;
            }

            @Override
            public Instant pidStartedAt() {
                return Instant.now();
            }

            @Override
            public CompletableFuture<Void> ended() {
                return end;
            }
        };
    }
}
