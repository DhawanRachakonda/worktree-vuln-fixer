# Spec Delta

## MODIFIED Requirements

### Requirement: One runtime, JDK 25
The kit (its installer, its tools, its validators and its tests) and the subscriber engine SHALL run on a JDK 25 or later and SHALL NOT need Node.js,
npm, Volta or any other runtime. `appmod.cmd` SHALL look for a JDK 25 or later and SHALL download a portable Temurin 25 only when none is found. The
JDK it finds or installs SHALL be recorded by `appmod setup` in the machine configuration, and the engine SHALL be built and started with that JDK,
not with whichever `java` the terminal finds. The
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

#### Scenario: The engine runs on the recorded JDK
- **WHEN** `appmod setup` found a JDK 25 that is not on the PATH and `appmod start` runs in a new terminal
- **THEN** the engine is built and started with that JDK, and the terminal's own `java` is not consulted
