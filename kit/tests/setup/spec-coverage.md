# Spec coverage: machine-setup, local-runtime-launcher, kit-java-runtime (change `appmod-setup-and-start`)

Every scenario of `openspec/changes/appmod-setup-and-start/specs/` and what proves it. All checks are in `tests/setup/SetupStartTests.java`
(`java tests/setup/SetupStartTests.java` from the kit folder, 48 checks, about three minutes); the programs run as separate processes with `APPMOD_HOME` in a
temporary folder, against a stand-in release server (an `HttpServer` on a loopback address), a stand-in `nats-server` (`StandInNats.java`: sends the `INFO` line and
answers `PING`) and a stand-in engine (`StandInEngine.java`, packed as a jar). No internet, no Docker. The commands are Windows only: elsewhere the program prints `SKIPPED`
and exits 0. **The real run** is [docs/evidence/setup-start-run.txt](../../../docs/evidence/setup-start-run.txt): the real download with its SHA-256 check, the real `nats-server`,
the real engine jar, a message with a header, and the engine ended from outside.

Mutation checks (one change each, restored afterwards): removing the hash comparison, the zip-slip check (all its clauses), the loopback binding, the recognition of a
running NATS, and the stop of a NATS that `start` began each made checks fail.

## machine-setup

| Scenario | Proof |
|---|---|
| First run on a clean machine | **Proven.** `setupDownloadsVerifiesAndSaves` (download, hash, unpack only the program, the archive deleted, the configuration written, no secret in it); the real run. |
| Run again | **Proven.** `setupRunTwiceDownloadsOnce` (one request in all, the answers kept, the configuration the same). |
| A `nats-server` already installed | **Proven.** `setupUsesANatsServerOnThePathAndBuildsTheEngine` (a file of that name on the PATH is used, nothing downloaded, no tools folder made). |
| Answers given as flags | **Proven.** `setupDownloadsVerifiesAndSaves`, `setupQuestionsAndFlags` (`--roots`, `--tool`, `--yes`). |
| Check only | **Proven.** `setupCheckChangesNothing` and `appmodCmdIsWiredToBothCommands` (exit 1, says what is missing, nothing written). |
| A folder that does not exist / a relative folder by flag / the file survives a failure | **Proven.** `setupQuestionsAndFlags` (missing folder named, relative refused and nothing saved, asked again through the answers file, a failed run leaves the earlier file as it was). |
| Hash matches / does not match / an archive that tries to leave the folder / unreachable download | **Proven.** `setupDownloadsVerifiesAndSaves`, `setupRefusesWrongHashAndEvilArchive` (both hashes shown and the file deleted; the entry `..\..\evil.exe` stops everything and nothing is written; the address named with the manual way; a version without a pinned hash refused). Mutation-checked. |
| Build succeeds / build fails / kit copied without the engine | **Proven.** `setupUsesANatsServerOnThePathAndBuildsTheEngine` (a stand-in Gradle wrapper: run with `JAVA_HOME` set to the recorded JDK, the program saved; a failing wrapper's own message shown, nothing saved, exit not 0), `setupWithoutAnEngineFolderSaysSo`. The real `gradlew bootJar` was run in the real run, where it found the engine up to date (a jar from an earlier build existed). |
| Repository outside the folders / no configuration yet (`init`) | **Proven.** `initWarnsOutsideTheSetupFolders`. |

## local-runtime-launcher

| Scenario | Proof |
|---|---|
| Start with everything set up / not set up / a file named in the configuration is gone | **Proven.** `startRunsBothAndStopsWhatItStarted` (the engine got only the saved settings and the recorded JDK), `startRefusals` (no configuration; the `nats-server` program gone is named), `appmodCmdIsWiredToBothCommands`; the real run. **Gap:** the engine program being gone is not tested separately (the same check covers both files). |
| NATS already running / started by `start` / something else holds the port / does not become ready / stale process id file | **Proven.** `startReusesARunningNats`, `startRunsBothAndStopsWhatItStarted` (loopback only, log file, stopped with the engine, pid file removed), `startRefusals` (a silent listener names the port; a server that never answers is given up on after 15 seconds and stopped; a stale `nats.pid` is removed). Mutation-checked: reuse, loopback, stop. |
| Only NATS / only the engine with NATS elsewhere / only the engine without NATS | **Proven.** `startNatsOnlyAndEngineOnly`, `startReusesARunningNats`. |
| Not reachable from the network | **Proven.** `startRunsBothAndStopsWhatItStarted` (the stand-in is told `-a 127.0.0.1`); the real run (`netstat` shows `LISTENING` on `127.0.0.1:4222` only). |

## kit-java-runtime

| Scenario | Proof |
|---|---|
| The engine runs on the recorded JDK | **Proven, partly.** The stand-in engine prints the JDK it runs on and it is the one in the configuration (`startRunsBothAndStopsWhatItStarted`), and the stand-in Gradle wrapper sees the recorded `JAVA_HOME`. A machine where the terminal's own `java` differs from the recorded one was not set up. |

## Gaps, stated plainly

1. Not run: a machine without any JDK 25 (the portable Temurin download is `appmod init`'s existing step and was not exercised again); the user PATH change (`--persist-path`) through `appmod setup`; a proxy or an antivirus blocking the download.
2. The engine's console was not used in the real run (the engine had no terminal); `appmod start` in a terminal, where the console works, was not run by a person.
3. Only Windows. On Linux the program prints `SKIPPED`.
