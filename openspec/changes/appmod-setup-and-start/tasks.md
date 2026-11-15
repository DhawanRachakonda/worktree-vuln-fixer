# Tasks

Every task says how it is verified. The commands are Windows only: the test programs print `SKIPPED` with the reason and exit 0 on another operating system.
Tests need no internet and no Docker (a stand-in release server and a stand-in `nats-server`); the real download and the real engine are one recorded run (section 7).

## 1. Machine configuration and the shared helpers

- [ ] 1.1 Add `kit/install/MachineConfig.java`: read and write `machine.properties` in `APPMOD_HOME` (default `%LOCALAPPDATA%\appmod`), written to a temporary file and moved into place so it is complete or absent; keys `javaHome`, `natsServer`, `natsPort`, `engineJar`, `allowedRoots`, `agentTool`, `dataDir`; validation of folders (absolute, existing, no control characters) and of the tool (`copilot` or `claude`); verify with tests for a round trip, a missing file, an invalid value, a file that was cut off, and that no value looks like a credential
- [ ] 1.2 Add the download helpers in the same folder: HTTPS-only download, SHA-256 of a file, and zip extraction that takes only `nats-server.exe`, refuses an entry that leaves the target folder and deletes the archive; verify with tests for a matching hash, a different hash (file deleted, nothing extracted), a zip-slip entry, a missing hash for a version, and a non-HTTPS address

## 2. `appmod setup`

- [ ] 2.1 Add `kit/install/AppmodSetup.java`: take the JDK found by `appmod.cmd` as an argument and record it; find `nats-server` (PATH, then `tools\nats-server-v<version>`), ask before downloading the pinned version (`APPMOD_NATS_VERSION` with `APPMOD_NATS_SHA256`, `APPMOD_NATS_BASE_URL` for the tests), verify and extract; verify with tests against a stand-in release server: first run downloads and records, second run downloads nothing, an installed `nats-server` is used, a wrong hash stops with both hashes, an unreachable address names itself and the manual alternative
- [ ] 2.2 Build the engine in setup: `engine\gradlew.bat bootJar` with `JAVA_HOME` set to the recorded JDK, check the jar, record it, show the build's error on failure, and accept `--engine-jar <path>` when there is no `engine` folder next to `kit`; verify with a test for `--engine-jar` (existing and missing file), for the missing-`engine` message, and (opt-in, when `engine\build\libs` exists or `-Dappmod.realBuild`) a real `bootJar`
- [ ] 2.3 Ask for the folders and the tool, with `--roots`, `--tool`, `--yes`, `--nats-port` and `--check`, keeping earlier answers as defaults; verify with tests for a valid answer, a missing folder (asked again), a relative folder by flag (stops, saves nothing), an answer from a file standing in for the terminal, `--yes`, `--check` (exit 1 when something is missing, nothing written), and a failure in the middle that leaves the earlier configuration intact
- [ ] 2.4 Hand the tools folders to the existing PATH persistence: print one final line `PATH_ADDITIONS=...` and nothing else changes the PATH; verify with a test that setup itself never changes the user PATH and prints that line

## 3. `appmod.cmd`

- [ ] 3.1 Add the commands `setup` and `start` to `kit/appmod.cmd` (parsing, `--help`, `version`), passing the detected JDK to setup and `start` to the launcher, and calling `:persist_paths` with the line setup printed (so `--persist-path` and `--no-persist` behave as in `init`); verify with the kit tests that run `appmod.cmd setup --check` and `appmod.cmd --help` and see the new text, and that `init` output is unchanged for a repository
- [ ] 3.2 Make `appmod init` warn when the configuration exists and the repository is outside its folders (and print nothing about folders when there is no configuration); verify with tests for both scenarios of the spec

## 4. `appmod start`

- [ ] 4.1 Add `kit/install/AppmodStart.java`: read the configuration (clear message for a missing or invalid file and for a program that is gone), decide about NATS by connecting and reading the first line (reuse, start in the background bound to `127.0.0.1` with log and process id files, error for another program on the port, 15-second readiness limit with the log's last lines), then run the engine in the foreground with the recorded JDK and only the settings named in the spec; verify with tests using the stand-in `nats-server` and a stand-in engine that prints the settings it received: both started and stopped in order, an existing server reused and left running, a closed port started and stopped when the engine ends, a non-NATS listener refused, a server that never answers, a stale process id file
- [ ] 4.2 Add `--nats` (foreground, prints the address, refuses a taken port, ends on interrupt) and `--engine` (says when no NATS answers, runs anyway), refuse unknown options with the list, and end a started NATS also when the process is interrupted; verify with tests for each scenario of `local-runtime-launcher`, including that NATS listens on `127.0.0.1` only and that no process is left behind after each test
- [ ] 4.3 Run it against the real engine program when it exists (`engine\build\libs`): opt-in test that starts `appmod start --engine` with the real jar and the stand-in NATS and sees the engine's subscriber connect and its console prompt; verify the engine log line for the connection

## 5. Tests, the gate and CI

- [ ] 5.1 Put the tests of sections 1 to 4 in `kit/tests/setup/SetupStartTests.java` (single file, JDK only, as the other kit tests), with the stand-in release server (`HttpServer`) and the stand-in `nats-server` and engine under `kit/tests/setup/`, each test in its own temporary `APPMOD_HOME`; verify that it passes on Windows and prints `SKIPPED` and exits 0 on Linux
- [ ] 5.2 Mutation-check: break the hash comparison, the zip-slip check, the loopback binding, the "reuse a running NATS" rule and the stop-on-exit, and see the matching tests fail, then restore each
- [ ] 5.3 Add the program to `scripts/Verify.java` (the kit group) and confirm `.github/workflows/ci.yml` runs it on Windows and Linux through `java scripts/Verify.java`; verify with a full `java scripts/Verify.java` run that ends `READY` and shows the new line in the summary

## 6. Documentation

- [ ] 6.1 Rewrite `engine/docs/setup.md` steps 5 and 6 as `appmod setup` and `appmod start` (what each asks and does, `--nats`, `--engine`, `--check`, where the configuration and logs are, the loopback-only note), keeping the manual way (a `nats-server` you start, `gradlew build`, `java -jar` with the environment variables) as the alternative; update its prerequisites list and the "does not install NATS" sentence; verify with `grep` that no step still tells the reader to start NATS by hand as the first choice
- [ ] 6.2 Update `README.md` (quick start: `appmod setup`, `appmod init <repo>`, `appmod start`), `kit/docs/install-and-appmod-cmd.md` (the command table, the help text, the new section on setup and start), `kit/README.md`, `CONTRIBUTING.md` (layout, how the new tests run) and `AGENTS.md` (how to test commands that download or start processes: stand-in release server and stand-in server, no internet, no Docker, never leave a process running, Windows-only skip); verify that every command shown in them was run, by `appmod --help` text equal to the docs and by section 7
- [ ] 6.3 Add `kit/tests/setup/spec-coverage.md` (every scenario of `machine-setup`, `local-runtime-launcher` and the `kit-java-runtime` change, with its test or the recorded run, and the gaps stated) and a row and a "Not proven" line in `docs/evidence.md`; verify that every test name cited exists

## 7. Real run and evidence

- [ ] 7.1 On Windows, with no `nats-server` on the PATH, run `appmod setup` for real (the real download and hash check, the real engine build, folders and tool answered), then `appmod start`, publish a report from the engine console to a sample repository and see a session recorded, then end the engine and see NATS stopped; record the output in `docs/evidence/setup-start-run.txt` and describe what was and was not run (no hosted services, one machine); verify that the file shows the hash check, the build, the NATS lifecycle and the session

## 8. Post and videos (the rules of `AGENTS.md`; videos are not re-recorded for this change)

- [ ] 8.1 Update the published post `C:/Projects/alumnx-ai-labs-training/dhawanrachakonda.github.io/posts/scanner-report-to-fix-branch.md` and `docs/social-posts.md` for `setup` and `start` (the "start NATS yourself" wording, the quick start, the Not proven list), copying the new log to the post's `posts/evidence/`, following that repository's AGENTS.md and its reviewer gate, without committing or pushing there; verify with the reviewer's verdict and that every number cites a log
- [ ] 8.2 No new video: the author decided that videos are re-recorded only when the execution flow changes drastically (AGENTS.md, rule 4); check that `docs/media/README.md` does not contradict the new commands and say in the final message that the videos were left alone; verify with `grep` that no doc claims a video shows `setup` or `start`

## 9. Final check

- [ ] 9.1 Run `java scripts/Verify.java` (and `--live` when the engine's NATS tests were touched) and `openspec validate appmod-setup-and-start --strict`; verify `READY` and that the final report says what was run for real and what was not
