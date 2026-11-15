package dev.subscriber.console;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Finds a command on the PATH the way the shell that will run it would: on Windows also with each executable extension. */
final class CommandFinder {

    private final String path;
    private final boolean windows;
    private final String pathExt;

    CommandFinder(String path, boolean windows, String pathExt) {
        this.path = path == null ? "" : path;
        this.windows = windows;
        this.pathExt = pathExt == null || pathExt.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathExt;
    }

    Optional<Path> find(String command) {
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names(command)) {
                try {
                    Path candidate = Path.of(directory.strip().replace("\"", "")).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (InvalidPathException _) {
                    // A PATH entry that is not a path cannot hold the command.
                }
            }
        }
        return Optional.empty();
    }

    private List<String> names(String command) {
        List<String> names = new ArrayList<>();
        names.add(command);
        if (windows) {
            for (String extension : pathExt.split(";")) {
                if (!extension.isBlank()) {
                    names.add(command + extension.strip());
                }
            }
            names.add(command + ".ps1");
        }
        return names;
    }
}
