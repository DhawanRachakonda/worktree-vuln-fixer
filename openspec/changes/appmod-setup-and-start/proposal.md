# Proposal

## Why

To run the engine today a person must know implementation details: download a `nats-server` and start it, then go to the `engine` folder,
build it with Gradle, set `SUBSCRIBER_ALLOWED_ROOTS` and the agent tool in environment variables, and start `java -jar` in a second
terminal. None of that is the product; it is plumbing. `appmod init` already prepares a machine for the kit (it finds or downloads JDK 25),
but nothing prepares the machine for the engine, and nothing starts it. The engine is also a per-machine program serving many repositories,
while `appmod init` works on one repository, so the missing step belongs in a new per-machine command, not in `init`.

## What Changes

- New command `appmod setup` (once per machine, Windows): finds JDK 25 (the same detection `init` uses, downloading a portable Temurin only
  when none is found), downloads a **pinned `nats-server`** into the appmod tools folder when none is found and verifies its SHA-256,
  builds the engine jar from the clone the command runs from (with the JDK it found), **asks which folders hold the person's
  repositories** (the engine's allowed roots) and which coding agent they use (Copilot or Claude Code), and saves all of it in one
  machine configuration file. Both answers can be given as flags for scripts. It offers to add the tools folders to the user PATH through
  the persistence step `init` already has. It never asks for or stores a credential.
- New command `appmod start [--nats|--engine]`: by default starts both. NATS is started in the background, bound to the loopback
  interface (or an already running NATS on the configured port is reused), and the engine in the foreground of the same terminal, because
  the engine's console is its interface. Ending the engine stops a NATS that `start` started. `--nats` runs only NATS (in the foreground);
  `--engine` runs only the engine and says so when no NATS answers. `start` reads the machine configuration, so it does not depend on
  the PATH of the terminal and needs no environment variables.
- `appmod init` is unchanged. It may print a warning when the repository lies outside the folders given to `setup`.
- The JDK found or installed is recorded, so `start` runs the engine with that JDK, not with whichever `java` the terminal finds.
- Docs: `README.md`, `engine/docs/setup.md` (steps 5 and 6 become `setup` and `start`, the manual way stays as the alternative),
  `kit/docs/install-and-appmod-cmd.md`, `kit/README.md`, `CONTRIBUTING.md`, `AGENTS.md`, evidence, spec coverage, the published post and the
  the rules in `AGENTS.md` (the videos are left alone: this is not a drastic change of the execution flow, by the author's decision).
- End-to-end tests for both commands (a stand-in release server and a stand-in `nats-server`, plus a recorded real run).
- Non-goals, stated on purpose: Linux and macOS (`appmod.cmd` is Windows only); a NATS token or TLS; an `appmod stop` command;
  publishing a prebuilt engine jar (the engine is built from the clone); updating NATS or the engine automatically.

## Capabilities

### New Capabilities
- `machine-setup`: what `appmod setup` finds, downloads, builds, asks and saves, and how it treats a download.
- `local-runtime-launcher`: what `appmod start` starts, in which order and for how long, how it reuses a running NATS, and what it tells the person.

### Modified Capabilities
- `kit-java-runtime`: the JDK that `appmod.cmd` finds or downloads is recorded for the launcher and used to build and run the engine.

## Impact

- Kit: `appmod.cmd` (the commands `setup` and `start`, help text), new single-file Java programs next to `kit/install/KitInstaller.java`
  (setup, start, the machine configuration and the zip/hash handling), new tests in `kit/tests/`, `scripts/Verify.java` (runs them).
- Engine: none in the code; it is started with the environment variables it already reads.
- CI: the kit tests run on Linux and Windows; the new ones skip with a reason on Linux (the commands are Windows only).
- Docs, evidence and posts as listed above (no new video).
- No new runtime: Java 25 only. `nats-server` is a third-party program the person already needs; it is downloaded, never shipped in the repository.
- Not proven until run for real: the download of the pinned `nats-server` from its release page, on Windows, with a real engine.
