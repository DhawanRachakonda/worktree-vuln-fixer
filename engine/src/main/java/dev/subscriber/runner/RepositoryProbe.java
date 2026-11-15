package dev.subscriber.runner;

import java.nio.file.Path;

/** Reads what the engine records about a repository when a session starts. */
public interface RepositoryProbe {

    /**
     * @param head  the commit {@code HEAD} points to, or null when it cannot be read
     * @param dirty whether the working tree has uncommitted changes, or null when that cannot be told
     */
    record RepositoryState(String head, Boolean dirty) {
    }

    RepositoryState probe(Path repository);
}
