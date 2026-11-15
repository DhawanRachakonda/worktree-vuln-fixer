package dev.subscriber.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Asks git for the commit and the state of the working tree. The repository path is passed as one argument in an argument list, never
 * through a shell, so its characters cannot change the command.
 */
@Component
public class GitRepositoryProbe implements RepositoryProbe {

    private static final int OUTPUT_LIMIT = 1 << 20;

    @Override
    public RepositoryState probe(Path repository) {
        String head = run(repository, "rev-parse", "HEAD");
        String status = run(repository, "status", "--porcelain");
        return new RepositoryState(head == null || head.isBlank() ? null : head.strip(), status == null ? null : !status.isBlank());
    }

    private static String run(Path repository, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readNBytes(OUTPUT_LIMIT), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
