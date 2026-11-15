# kit-java-runtime Specification

## Purpose
The kit and the engine need one runtime, a JDK 25, and nothing else: no Node.js, no npm.

## Requirements

### Requirement: One runtime, JDK 25
The kit (its installer, its tools, its validators and its tests) and the subscriber engine SHALL run on a JDK 25 or later and SHALL NOT need Node.js,
npm, Volta or any other runtime. `appmod.cmd` SHALL look for a JDK 25 or later and SHALL download a portable Temurin 25 only when none is found. The
Copilot CLI SHALL be installed with WinGet by default. The repository SHALL contain no Node program (no `.cjs`, `.mjs` or `package.json`) that a user or an agent has to run.
The one exception is a third-party component: the GitHub Copilot app modernization plugin runs its hooks with `node` and its MCP server with `npx`, so
`appmod.cmd` SHALL install Node only for that plugin, only when the tool is Copilot, and SHALL say that the plugin is the reason.

#### Scenario: A machine without Node
- **WHEN** a machine has a JDK 25 and no Node, and the kit is installed for Claude Code and a security audit is validated
- **THEN** every step works, and nothing asks for Node or npm

#### Scenario: Node only for the Copilot plugin
- **WHEN** `appmod init` is run for Copilot on a machine without Node
- **THEN** Node is installed as a prerequisite of the app modernization plugin, the message says so, and no kit tool or engine function needs it

#### Scenario: Claude Code needs neither the plugin nor Node
- **WHEN** `appmod init` or `appmod kit` is run with the tool Claude Code
- **THEN** neither Volta, Node, npm, the Copilot CLI nor the plugin is installed or checked

#### Scenario: An older JDK
- **WHEN** the installer or a kit tool is started on a JDK older than 25
- **THEN** it stops with a message that names the version it needs and the one it found

#### Scenario: No Node in the repository
- **WHEN** the repository is searched for `.cjs`, `.mjs` and `package.json` files and for a `node` command in a script, a skill or an agent
- **THEN** none is found in the kit or the engine (the Maven web harness keeps its browser script `app.js`, which is not run with Node)

### Requirement: The validators are Java and behave as the vendored ones
The security-audit skill's findings validator and coverage-ledger validator SHALL be single-file Java programs (`ValidateFindings.java`,
`ValidateCoverageLedger.java`) with the same rules, limits, messages, standard output and exit codes as the vendored JavaScript originals, and
`ValidateRun.java` SHALL run both on a run directory and print the same summary line. They SHALL open a file without following links on every
operating system, refuse a file that is a link or is not a regular file, refuse input over the size limit and input that is not valid UTF-8,
and SHALL NOT have a weaker mode on any operating system. The skill's text SHALL tell the agent to run them with `java`, and `UPSTREAM.md` SHALL list every local edit.

#### Scenario: Same verdicts as the original
- **WHEN** the same findings and ledger files, valid and invalid, are checked by the original and by the Java port
- **THEN** the exit code, the standard output and the standard error are the same, apart from the program name in a usage message

#### Scenario: Windows
- **WHEN** a run directory is validated on native Windows
- **THEN** both validators run unchanged, the summary line says `valid` true and does not say `weakened`, and a symlink or junction as input is refused

#### Scenario: A real validation error
- **WHEN** a finding or ledger unit breaks a rule
- **THEN** the validator prints the rule's message with the path of the value, exits 1, and the run is not reported as valid
