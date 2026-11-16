# Spec Delta

## Purpose

Prepares a Windows machine once so that the engine can be started without the person knowing how NATS, Gradle or the engine's settings work:
the JDK, the NATS server, the engine program and the engine's two basic answers are found or made, checked and saved in one place.

## ADDED Requirements

### Requirement: `appmod setup` prepares the machine for the engine
The system SHALL provide the command `appmod setup`, run once per machine and not tied to a repository. It SHALL find a JDK 25 or later with the
same detection `appmod init` uses and download a portable Temurin only when none is found; SHALL find a `nats-server` program on the PATH or
in the appmod tools folder and download the pinned one only when none is found; SHALL build the engine program from the clone it runs from, using
the JDK it found; SHALL ask which folders hold the person's repositories and which coding agent they use (Copilot or Claude Code); and SHALL
save the results in the machine configuration file (see the next requirement). Each step SHALL check first and change nothing that is already
fine, so running the command again is safe and keeps the earlier answers as defaults. It SHALL print what it found, what it did and what it
saved, and SHALL NOT ask for, print or store a password, token or other credential. It SHALL change nothing outside the appmod folders and,
only with the person's consent, the user PATH.

#### Scenario: First run on a clean machine
- **WHEN** `appmod setup` runs on a machine with no `nats-server` and no engine program, the person gives `C:\src` and `claude`
- **THEN** the pinned `nats-server` is downloaded and verified, the engine is built, and the configuration file names the JDK, the
  `nats-server`, the engine program, the folder `C:\src` and the tool `claude`

#### Scenario: Run again
- **WHEN** it runs a second time with nothing changed
- **THEN** nothing is downloaded, the earlier answers are offered as defaults, the engine build is up to date, and the configuration is the same

#### Scenario: A `nats-server` already installed
- **WHEN** a `nats-server` is on the PATH
- **THEN** it is used (its version is shown), nothing is downloaded, and its path is recorded

#### Scenario: Answers given as flags
- **WHEN** it runs with `--roots C:/src,D:/work --tool copilot --yes`
- **THEN** it asks no question and saves those answers and every default

#### Scenario: Check only
- **WHEN** it runs with `--check`
- **THEN** it changes nothing, prints each item as found or missing, and exits with status 1 when something is missing

### Requirement: The folders and the tool are asked, validated and saved
The system SHALL accept a folder only when it is an absolute path to an existing folder without control characters, and SHALL refuse a
relative path, a missing folder or a file with the reason and ask again (or stop when the answer came from a flag). It SHALL accept as tool only
`copilot` or `claude`. The machine configuration SHALL be one file, `machine.properties` in the appmod folder (default
`%LOCALAPPDATA%\appmod`), holding the JDK, the NATS program and port, the engine program, the folders (as the engine's `SUBSCRIBER_ALLOWED_ROOTS`
takes them), the tool and the engine's data folder, and no secret. It SHALL be written completely or not at all.

#### Scenario: A folder that does not exist
- **WHEN** the person answers `C:\nope`
- **THEN** the answer is refused naming the folder, the question is asked again, and nothing is saved until a valid answer is given

#### Scenario: A relative folder given by flag
- **WHEN** `--roots src` is given
- **THEN** setup stops with the reason and saves nothing

#### Scenario: The file survives a failure
- **WHEN** setup fails after the answers were given and before it finished
- **THEN** the configuration file is either the earlier complete one or absent, never half written

### Requirement: A download is pinned, verified and extracted safely
The system SHALL download `nats-server` only over HTTPS (a loopback address may use plain HTTP, which is how the tests serve a stand-in release), only a version that is pinned together with the SHA-256 of its release archive, and
SHALL compare the hash of the downloaded archive before extracting anything. A version without a pinned hash SHALL be refused. A hash that
does not match SHALL delete the download, extract nothing and stop with both hashes shown. Extraction SHALL refuse an archive entry whose
path leaves the target folder, SHALL take only the `nats-server` program, and SHALL delete the archive afterwards. The download SHALL show the
address it uses, and a failure SHALL name that address and say how to install `nats-server` by hand.

#### Scenario: Hash matches
- **WHEN** the downloaded archive has the pinned hash
- **THEN** `nats-server.exe` is extracted into `tools\nats-server-v<version>` and the archive is deleted

#### Scenario: Hash does not match
- **WHEN** the downloaded archive has another hash
- **THEN** the file is deleted, no program is extracted, the exit status is not 0, and both hashes are shown

#### Scenario: An archive that tries to leave the folder
- **WHEN** the archive holds an entry named `..\..\evil.exe`
- **THEN** nothing is extracted and setup stops with that reason

#### Scenario: Unreachable download
- **WHEN** the address cannot be reached
- **THEN** setup names the address, says that a `nats-server` installed by hand and put on the PATH is found, and changes nothing else

### Requirement: The engine program is built with the recorded JDK
The system SHALL build the engine program from the `engine` folder next to `kit` with the Gradle wrapper, with `JAVA_HOME` set to the JDK it
recorded, SHALL check that the program exists afterwards and record its path, and SHALL show the build's own error when it fails. When there is no `engine`
folder next to `kit`, it SHALL say so and accept `--engine-jar <path>` for a program built elsewhere, checking that the file exists.

#### Scenario: Build succeeds
- **WHEN** the build finishes
- **THEN** the engine program's path is in the configuration

#### Scenario: Build fails
- **WHEN** the build fails
- **THEN** setup shows the build's error, saves no engine path, and exits with a status other than 0

#### Scenario: Kit copied without the engine
- **WHEN** there is no `engine` folder next to `kit`
- **THEN** setup says the engine cannot be built here and that `--engine-jar` names a program built elsewhere

### Requirement: Setup and init agree
`appmod init` SHALL stay unchanged in what it installs. When the machine configuration exists and the repository given to `init` is not inside
one of its folders, `init` SHALL print a warning that the engine will refuse reports from that repository and how to add the folder (`appmod setup`),
and SHALL still install the kit.

#### Scenario: Repository outside the folders
- **WHEN** `appmod init D:\other\repo` runs and the configuration names only `C:\src`
- **THEN** the kit is installed and a warning names `D:\other\repo` and `C:\src`

#### Scenario: No configuration yet
- **WHEN** `appmod init` runs on a machine where `setup` was never run
- **THEN** it prints nothing about folders and installs the kit as before
