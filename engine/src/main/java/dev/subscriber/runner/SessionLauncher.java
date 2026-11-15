package dev.subscriber.runner;

import java.io.IOException;
import java.nio.file.Path;

/** Starts the agent for one session. The window and headless runners implement it. */
public interface SessionLauncher {

    /**
     * @param sessionId  the session's id (also the agent's session id)
     * @param repository the repository the agent works in
     * @param agent      the agent to start
     * @return the started session; the caller waits for {@link RunningSession#ended()} and then reads the exit record
     */
    RunningSession launch(String sessionId, Path repository, String agent) throws IOException;
}
