# kit-install-targets Specification

## Purpose
Lets one kit be installed for GitHub Copilot, Claude Code or both, with the Claude Code files rendered from the kit's own sources, so
that there is one copy of each agent and skill to maintain.

## Requirements

### Requirement: The tool is chosen at install time
The installer SHALL accept `--tool copilot`, `--tool claude` or `--tool both`, and SHALL refuse any other value without writing anything.
Without the option it SHALL behave as `copilot`: the files it places and their contents SHALL be those it placed before the option
existed.

#### Scenario: Default is Copilot
- **WHEN** the installer runs without `--tool`
- **THEN** it places the same files as `--tool copilot`, byte for byte (plus the install record), and creates nothing under `.claude/`

#### Scenario: Unknown tool
- **WHEN** the installer runs with `--tool vim`
- **THEN** it exits with an error that names the allowed values and writes nothing

#### Scenario: Both tools
- **WHEN** the installer runs with `--tool both`
- **THEN** the Copilot files and the Claude Code files are both placed

### Requirement: Claude Code agents are rendered from the Copilot agents
For Claude Code the installer SHALL write `.claude/agents/<name>.md` for each kit agent from the same source file as the Copilot agent, with
the same name, description and body. It SHALL map the `tools` list to Claude Code tool names (`read` to `Read`, `search` to `Grep` and `Glob`,
`edit` to `Edit` and `Write`, `shell` to `Bash` and `PowerShell`, `agent` to `Agent`), SHALL drop keys that only Copilot knows, SHALL refuse a
tool name it cannot map, and SHALL rewrite the kit's own paths in the text to the Claude Code locations. It SHALL NOT install the agent
`appmod-harness`, which depends on the GitHub Copilot-only plugin it delegates to, and SHALL say so in its report.

#### Scenario: Tools are mapped
- **WHEN** the agent `security-fixer` (tools `read`, `search`, `edit`, `shell`, `agent`) is rendered
- **THEN** `.claude/agents/security-fixer.md` has the same name and description, the tools `Read, Grep, Glob, Edit, Write, Bash, PowerShell, Agent`,
  no `user-invocable` key, and the same body apart from the rewritten paths

#### Scenario: Unknown tool name
- **WHEN** a kit agent lists a tool the installer has no mapping for
- **THEN** the installer fails with a message that names the agent and the tool, and writes nothing for Claude Code

#### Scenario: The front door is left out
- **WHEN** the installer runs for Claude Code
- **THEN** no `appmod-harness` agent is written under `.claude/agents/`, and the report says that it needs the Copilot plugin

### Requirement: Claude Code skills, rules and memory file
For Claude Code the installer SHALL copy each kit skill to `.claude/skills/<name>/` (rewriting the kit's own paths in its text files only), SHALL
write each instruction file as `.claude/rules/<name>.md` (`applyTo: "**"` becomes no path restriction, any other `applyTo` becomes `paths`), and SHALL add a
marked block to `CLAUDE.md` that names the installed agents, rules, skills and tools. It SHALL NOT overwrite an existing `CLAUDE.md`: it
appends the block once and never twice. The tool-neutral support files (`.github/appmod/tools/` and `.github/modernize/rulebook/`) SHALL be installed
in the same place for every tool.

#### Scenario: Skills and rules
- **WHEN** the installer runs for Claude Code
- **THEN** `.claude/skills/security-audit/SKILL.md` and the other kit skills exist, the instruction files are in `.claude/rules/`, and
  the Java tools are in `.github/appmod/tools/`

#### Scenario: Existing CLAUDE.md
- **WHEN** the target already has a `CLAUDE.md`
- **THEN** its content is kept, the block is appended once, and a second run appends nothing

#### Scenario: No stale paths
- **WHEN** the Claude Code files are written
- **THEN** none of them refers to `.github/agents/`, `.github/skills/` or `.github/instructions/`

### Requirement: The installer stays safe and repeatable
For every tool the installer SHALL report identical files as unchanged, SHALL keep a differing existing file unless `--force` is given, SHALL
write nothing with `--dry-run`, and SHALL leave the target unchanged on a second run. With `--with-mcp` it SHALL write `.vscode/mcp.json` for
Copilot and `.mcp.json` for Claude Code, naming the same MCP server version.

#### Scenario: Second run
- **WHEN** the installer runs twice with the same options
- **THEN** the second run reports every file as unchanged or merged and writes nothing

#### Scenario: A file the person edited
- **WHEN** a generated file in the target differs from what the installer would write, and `--force` is not given
- **THEN** the file is kept and the report says it was skipped

#### Scenario: Dry run
- **WHEN** the installer runs with `--dry-run`
- **THEN** nothing is written, and the report lists what would be

#### Scenario: MCP configuration
- **WHEN** the installer runs with `--with-mcp` for both tools
- **THEN** `.vscode/mcp.json` and `.mcp.json` both exist and name the same server package and version

### Requirement: The install is recorded and can be upgraded
A repository install SHALL write `.appmod/kit.json` (which the installer git-ignores with the rest of `.appmod/`) naming the tool and the SHA-256 of every
file the installer placed, and SHALL write it only when it writes the files (not with `--dry-run`, not for `--user`). The option `--upgrade` SHALL read that
record, SHALL use the recorded tool, SHALL overwrite a file only when it still has the recorded content (or `--force` is given), and SHALL report a file
that differs from the record as `skip (edited)` and keep it. It SHALL refuse, without writing anything, when there is no record, when it is combined
with `--user`, or when `--tool` names another tool than the record. The Windows setup script `appmod.cmd` SHALL accept `--tool`, SHALL ask which tool on
a new install when it is not given (and install for Copilot with `--yes`), and SHALL use `--upgrade` for `appmod upgrade kit`.

#### Scenario: The record
- **WHEN** the installer runs for Claude Code in a repository
- **THEN** `.appmod/kit.json` names `claude` and holds the SHA-256 of each placed file, and a dry run or a user install writes none

#### Scenario: Upgrade with edits
- **WHEN** the kit has newer versions of two agents and the person edited the installed copy of one of them, and the installer runs with `--upgrade`
- **THEN** the untouched agent gets the newer content, the edited one is kept and reported as `skip (edited)`, the record holds the new hash for the first and the old one for the second, and a second run changes nothing

#### Scenario: Upgrade without a record or with another tool
- **WHEN** `--upgrade` runs in a repository without `.appmod/kit.json`, or with `--tool copilot` where the record says `claude`, or with `--user`
- **THEN** the installer exits with an error that says why and writes nothing

#### Scenario: appmod.cmd
- **WHEN** `appmod kit <dir> --tool claude`, `appmod kit <dir>` with the answer Claude Code, and `appmod upgrade kit <dir>` are run
- **THEN** the files are installed for Claude Code, the record names it, and the upgrade applies only to unedited files

### Requirement: The kit's tools work wherever the skills are installed
The Java tools of the kit that need the vendored skill SHALL find it in `.github/skills` or in `.claude/skills`, whichever the install placed, without an
option.

#### Scenario: Validating an audit run after a Claude Code install
- **WHEN** `ValidateRun.java` runs in a repository installed for Claude Code only, without `--skill-dir`
- **THEN** it finds the skill in `.claude/skills/security-audit` and validates the run

### Requirement: User-level installation for Claude Code
With `--user` and the tool Claude Code the installer SHALL place the agents in `~/.claude/agents/` and the skills in `~/.claude/skills/`, and the Java tools
beside them as for Copilot, and SHALL say that instructions are repository-level.

#### Scenario: User install
- **WHEN** the installer runs with `--user --tool claude`
- **THEN** the agents and skills are in the user's `.claude` folder and nothing is written to the current repository
