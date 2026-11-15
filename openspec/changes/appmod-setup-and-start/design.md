# Design

## Context

See proposal.md for why. Today `kit/appmod.cmd` (a Windows batch file in the clone) has the commands `init`, `doctor`, `upgrade`, `plugin`,
`kit` and `version`; `init` checks and installs the machine's prerequisites for one repository, among them JDK 25 (step 8: reuse `JAVA_HOME`,
the PATH, `~/.jdks` and the tools folder, else download a portable Temurin into `%APPMOD_HOME%\tools` and offer to add it to the user PATH, with
`--persist-path` and `--no-persist`). `APPMOD_HOME` defaults to `%LOCALAPPDATA%\appmod`. The engine is built from the clone with
`engine\gradlew.bat` (a Gradle toolchain for Java 25) into `engine\build\libs\subscriber-engine-<version>.jar`, needs `SUBSCRIBER_ALLOWED_ROOTS`
and optionally `SUBSCRIBER_AGENT_TOOL`, `NATS_URL` and a permission mode, and has an interactive console that is its user interface. The
kit's tools are single-file Java programs with no `package` line and are run with `java File.java`; other source files in the same folder are
found by the launcher (as `ValidateFindings.java` finds `Json.java`). Windows is the only supported platform for `appmod.cmd`.

## Goals / Non-Goals

**Goals:**
- A person can go from a fresh clone to a running engine with `appmod setup` once and `appmod start` every day, knowing nothing about NATS
  or Gradle.
- The same Java 25 that `init` found is the one that builds and runs the engine.
- Nothing is downloaded without a pinned version and a checked hash, and nothing is written outside the appmod folders and the user PATH.
- The behaviour is testable without the internet: both commands can be run against a stand-in release server and a stand-in `nats-server`.

**Non-Goals:**
- Linux and macOS; a NATS token, TLS or authentication; an `appmod stop` command; a prebuilt engine jar; automatic updates of NATS or the engine;
  changing what `appmod init` installs; running more than one engine per data directory (the engine already refuses it).

## Decisions

**1. Two new per-machine commands; `init` stays per repository.** `setup` and `start` do not take a repository. Moving the machine-level steps
of `init` (Git, Copilot, the plugin) into `setup` would change what current users run, so it is not done; `setup` only adds the engine's
needs and reuses `init`'s JDK detection. Alternative: put NATS and the engine into `init`, rejected because the engine serves many repositories
and `init` would ask for machine settings again for every repository.

**2. The logic is Java; `appmod.cmd` forwards.** New single-file programs in `kit/install/` (`AppmodSetup.java`, `AppmodStart.java`, and
`MachineConfig.java` with the file, zip and hash handling) are run with the JDK that `appmod.cmd` already found for `KitInstaller`. Downloads,
SHA-256, safe zip extraction, process control and the readiness check are far simpler and testable in Java than in batch, and the kit's
tests are Java. `appmod.cmd` only parses the command and calls the program. The JDK detection stays in the batch file (it must run before any
Java exists); setup receives the result as an argument and records it. The user PATH is changed only by the existing persistence step: the
setup program prints the folders to add as one final machine-readable line (`PATH_ADDITIONS=...`), and `appmod.cmd` hands it to its existing
`:persist_paths`, so there is one implementation of that and the `--persist-path` / `--no-persist` flags behave the same.

**3. One machine configuration file: `%APPMOD_HOME%\machine.properties`.** A Java properties file (no parser to write, comments allowed, a
person can read and fix it): `javaHome`, `natsServer` (path of the program), `natsPort` (default 4222), `engineJar`, `allowedRoots` (comma
separated absolute paths, as `SUBSCRIBER_ALLOWED_ROOTS` takes them), `agentTool` (`copilot` or `claude`), `dataDir` (default
`%APPMOD_HOME%\engine-data`). It holds no secret. It is written by `setup` (replacing the file atomically) and only read by `start`. A missing
or invalid file makes `start` stop with "run `appmod setup`" and the reason. `setup` run again keeps the earlier answers as defaults.

**4. NATS is downloaded pinned, verified, and extracted safely.** `appmod setup` looks for `nats-server` on the PATH and in the tools folder
(`%APPMOD_HOME%\tools\nats-server-v<version>\`); when neither has one it asks (unless `--yes`) and downloads
`https://github.com/nats-io/nats-server/releases/download/v<version>/nats-server-v<version>-windows-amd64.zip`. The version and the expected
SHA-256 of that zip are pinned together in the source (variables `APPMOD_NATS_VERSION` and `APPMOD_NATS_SHA256` override both, and a version
without a hash is refused: an unverified download is never made). Extraction refuses an entry whose path leaves the target folder (zip slip),
takes only `nats-server.exe`, and the downloaded zip is deleted afterwards. A hash that does not match deletes the file and stops with
both hashes shown. A download base URL variable (`APPMOD_NATS_BASE_URL`) exists so the tests can serve a stand-in; it is documented as a test
aid and still goes through the hash check. A found program is used as it is (its version is only shown). Alternative: Docker, rejected for
people (a heavy daemon that may not run); it remains how the tests of the engine get a real server.

**5. The engine is built from the clone, with the recorded JDK.** `setup` runs `engine\gradlew.bat bootJar` with `JAVA_HOME` set to the
recorded JDK (Gradle then finds the Java 25 toolchain without any user-level Gradle setting), checks that the jar exists and records its
path. The clone is the folder above `kit\` where `appmod.cmd` lives; when there is no `engine\` next to it (the kit was copied alone) setup
says so and offers `--engine-jar <path>` for a jar built elsewhere. Run again, `bootJar` is up to date and cheap. Alternative: a release
asset with a checksum, deferred: it needs a release process and versioning that the project does not have yet, and the commands would not change.

**6. `setup` asks two questions and validates them.** Folders (default: the answer saved earlier; nothing by default on a first run) and
tool (`copilot` default). Each folder must be an absolute path to an existing folder; a relative path, a missing folder, a file or a path
with a control character is refused with the reason and asked again (or the run stops when `--yes` / flags were used). `--roots a,b` and
`--tool claude` answer them for scripts; `--yes` accepts the defaults and every download question. `--check` changes nothing and prints each
item as found or missing (exit 1 when something is missing), like `appmod doctor`.

**7. `start` is a small supervisor, not a service.** Order: read the configuration; make sure NATS answers on `natsPort` — the check connects
and reads the server's first line (`INFO {...}`): an answering NATS is **reused**, a closed port starts `nats-server -a 127.0.0.1 -p <port>`
in the background (output to `%APPMOD_HOME%\run\nats.log`, process id in `run\nats.pid`) and waits up to 15 seconds for the first line, and
an open port that is not NATS is an error that names the port. Then the engine runs in the **foreground** of the terminal (`java -jar` of the
recorded JDK, input and output inherited, so its console works), with `SUBSCRIBER_ALLOWED_ROOTS`, `SUBSCRIBER_AGENT_TOOL`,
`SUBSCRIBER_DATA_DIR` and `NATS_URL=nats://127.0.0.1:<port>` set from the configuration and nothing else changed. When the engine ends, a NATS
that this `start` began is stopped (a shutdown hook covers Ctrl+C and a closed window); one that was already running is left alone. `--nats`
runs only NATS in the foreground (its output on the terminal, Ctrl+C stops it) and says the URL; `--engine` runs only the engine, and when no
NATS answers it says so and runs it anyway (the engine retries its connection, as it does today). `--nats` and `--engine` together are
the default. Exit code: the engine's. A stale `nats.pid` (the process is gone or is not a `nats-server`) is deleted, never killed blindly.

**8. Loopback only, no token.** NATS is bound to `127.0.0.1`, so nothing on the network can publish. A token would have to be passed to
the engine and to every publisher and is a non-goal here; the engine's allowed-roots and path checks stay the control for a local process.
This is stated in the docs.

**9. Tests need no internet and no Docker.** A test program for each command runs the real Java programs as separate processes with
`APPMOD_HOME` pointed at a temporary folder: a stand-in release server (the JDK's `HttpServer` serving a zip that holds a small stand-in
`nats-server`: a `.cmd` that starts a Java program which listens, sends an `INFO` line and answers `PING` with `PONG`, enough for the
readiness check and for the engine's client to connect), a stand-in Gradle wrapper is **not** used: the engine build is tested by a real
`bootJar` in the opt-in test and by an `--engine-jar` run in the default test. The commands are Windows only, so on another operating system
the tests print `SKIPPED` with the reason and exit 0, which keeps `Verify.java` green on Linux CI. A real run (the real download, the
real build, a real engine publishing through it) is recorded once in `docs/evidence`.

## Risks / Trade-offs

- [A pinned NATS version ages] → the pin is one place (a variable); updating it is a one-line change plus the hash; docs say so.
- [The hash in the source is trusted as much as the repository] → it protects against a corrupted or swapped download, not against a bad
  commit; stated in the docs.
- [Building the engine needs network (Gradle, dependencies) and time] → `setup` says what it is doing and shows Gradle's failure; a prebuilt
  jar is the recorded future option.
- [A background `nats-server` can outlive a hard-killed terminal] → the pid file lets the next `start` reuse or clean it; no `stop` command (non-goal).
- [Port 4222 is taken by something else] → refused with the port named; `natsPort` is in the file and `setup --nats-port` sets it.
- [Antivirus or a proxy blocks the download] → the failure names the URL and the manual alternative (install `nats-server` yourself and
  put it on the PATH; `setup` finds it).
- [Two sources of truth for the JDK] → there is one: `setup` records what `init`'s detection found; `start` never searches.

## Migration Plan

Nothing changes for current users: `init`, `doctor` and the manual way of starting NATS and the engine keep working and stay documented as
the alternative. A person adopts the commands by running `appmod setup` once. Rollback is removing the two commands and their files;
the machine configuration and the tools folder are outside the repository and harmless.
