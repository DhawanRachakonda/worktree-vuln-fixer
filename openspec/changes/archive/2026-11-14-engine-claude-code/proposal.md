# Proposal

## Why

The kit can now be installed for Claude Code, and its agents run there, but the subscriber-engine, which turns an audit report into a running fix session, can
only start the GitHub Copilot CLI. A team that uses Claude Code as its coding agent cannot use the engine, and "supports Copilot and Claude Code" is true of
the agents but not of the application that runs them. The engine should run either tool, chosen by one setting, with the same sessions, worktrees, states, console and API.

## What Changes

- A new setting `subscriber.agent-tool` (`SUBSCRIBER_AGENT_TOOL`): `copilot` (the default, so nothing changes for existing setups) or `claude`. A second setting,
  `subscriber.claude.command` (`SUBSCRIBER_CLAUDE_COMMAND`, default `claude`), names the Claude Code command.
- For `claude` the engine generates launch scripts that run `claude` (headless: `claude -p`, with `--output-format stream-json` so the run is kept as a log; window:
  interactive `claude` with the prompt as its first message; reopen: `claude --resume <session id>`), with the same session id, agent name, session folder, exit
  record and states as for Copilot.
- The permission settings apply to either tool and get tool-neutral environment names (`SUBSCRIBER_PERMISSION_MODE`, `SUBSCRIBER_ALLOWED_TOOLS`,
  `SUBSCRIBER_DENIED_TOOLS`; the `SUBSCRIBER_COPILOT_*` names keep working). `allow-all-tools` becomes `--allow-all-tools` for Copilot and `--permission-mode bypassPermissions` for
  Claude Code; the allow-list rules (written in the tool's own syntax: `shell(git:*)` for Copilot, `Bash(git *)` for Claude Code) become `--allow-tool`/`--deny-tool` or `--allowedTools`/`--disallowedTools`.
  The headless guard applies to both.
- The session records which tool ran it (the `engine` field: `copilot` or `claude`), `reopen` uses the tool that ran that session (not the current setting),
  `config` and `doctor` show and check the selected tool's command.
- **Not changed:** the agents (the same `security-fixer` runs under either tool), the NATS subjects, the API shape, the data model.

## Capabilities

### New Capabilities
- `agent-tool-selection`: choosing the coding agent the engine runs, and how each tool is started, resumed and given its permissions.

### Modified Capabilities

None as a delta: the existing capabilities describe "the agent" and "the Copilot CLI" in places; this change states the tool choice as a capability of its own,
as earlier changes did.

## Impact

- `subscriber-engine`: `SubscriberProperties` (two settings), `LaunchPlan` and `LauncherScripts` (a tool), four new launcher templates, the three launchers and the
  reopen path, `IntakeService` (the recorded engine), `Doctor`, `ConsoleReports`, `application.yml`, README and `docs/` (operations, connecting, spec-coverage), tests and golden files.
- No migration: the `engine` column already exists and holds free text.
