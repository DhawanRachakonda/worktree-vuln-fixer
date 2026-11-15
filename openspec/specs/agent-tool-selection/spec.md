# agent-tool-selection Specification

## Purpose
Lets the engine run either GitHub Copilot CLI or Claude Code as the coding agent of a fix session, chosen by one setting, with the same sessions,
worktrees, states, console and API.

## Requirements

### Requirement: The coding agent is chosen by a setting
The system SHALL run the tool named by the setting `subscriber.agent-tool` (`copilot` or `claude`; default `copilot`) for every session it starts, and SHALL stop the
start on any other value with a message that names the setting and the allowed values. The Claude Code command SHALL be taken from `subscriber.claude.command`
(default `claude`) and SHALL be a plain command name, like the Copilot command. The session record SHALL name the tool that ran it, and a session SHALL be
reopened with the tool recorded for it, not with the one currently configured.

#### Scenario: Default is Copilot
- **WHEN** the engine runs without the setting
- **THEN** sessions are started with the Copilot command exactly as before, and are recorded with the engine `copilot`

#### Scenario: Claude Code selected
- **WHEN** the setting is `claude`
- **THEN** a session is started with the Claude Code command and recorded with the engine `claude`

#### Scenario: Invalid value
- **WHEN** the setting is `vim`, or the Claude Code command contains a character other than letters, digits and `_ . -`
- **THEN** the engine does not start, and the message names the setting

#### Scenario: Reopening after a change of tool
- **WHEN** a session that ran with Copilot is reopened while the setting says `claude`
- **THEN** the window resumes it with the Copilot command, and the other way round

### Requirement: Claude Code is started like Copilot
For `claude` the system SHALL generate the same kinds of launch script as for Copilot (a window, headless and reopen) with the same session id, agent name,
session folder, prompt file, exit record and states. The headless script SHALL run `claude -p` with `--output-format stream-json` so that the run is kept
in the session's log; the window script SHALL start `claude` interactively with the prompt as its first message and end when the person leaves it; the reopen
script SHALL run `claude --resume <session id>`. The session id SHALL be passed as `--session-id`, so that `--resume` finds the conversation.

#### Scenario: Headless Claude Code
- **WHEN** a session starts headless with `claude`
- **THEN** the script runs `claude -p` with the agent, the session id, the session folder as an additional directory and the stream-json output, writes the exit record, and the session ends `DONE`, `PARTIAL`, `NEEDS_INPUT` or `ERROR` by the same rules as for Copilot

#### Scenario: Window Claude Code
- **WHEN** a session starts in a window with `claude`
- **THEN** the window runs `claude` interactively with the agent and the prompt, and the session ends when the person leaves Claude Code

#### Scenario: Reopen Claude Code
- **WHEN** a finished session that ran with `claude` is reopened
- **THEN** a window runs `claude --resume` with that session's id in the session's working folder, and records nothing but one event

### Requirement: Claude Code does not inherit another Claude Code session
The scripts that start Claude Code SHALL remove the environment variables that name a running Claude Code session (`CLAUDECODE`, `CLAUDE_CODE_CHILD_SESSION`,
`CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_SESSION_ATTENDED`, `CLAUDE_CODE_ENTRYPOINT`, `CLAUDE_CODE_SSE_PORT`, `CLAUDE_CODE_MESSAGING_SOCKET`, `CLAUDE_PID`) before they start it, and SHALL keep every other
variable, in particular credentials. The scripts for Copilot SHALL NOT change.

#### Scenario: Engine started from inside Claude Code
- **WHEN** the engine was started from a Claude Code session and a session is run with `claude`
- **THEN** the agent process does not see that session's variables, still sees its credentials, and its conversation is saved so that `reopen` can resume it

### Requirement: Permissions for either tool
The permission mode and the tool rules SHALL apply to whichever tool runs. With `allow-all-tools` the system SHALL pass `--allow-all-tools` to Copilot and
`--permission-mode bypassPermissions` to Claude Code; with `ask` it SHALL pass no permission argument (the person approves in the window), and with the
allow-list it SHALL pass the allow rules and the deny rules in the tool's own syntax as `--allow-tool` and `--deny-tool` (Copilot) or as `--allowedTools` and
`--disallowedTools` (Claude Code), each as a single quoted argument. The settings SHALL be readable under tool-neutral environment names
(`SUBSCRIBER_PERMISSION_MODE`, `SUBSCRIBER_ALLOWED_TOOLS`, `SUBSCRIBER_DENIED_TOOLS`) as well as the `SUBSCRIBER_COPILOT_*` names. The rule validation and the headless guard SHALL apply to both tools.

#### Scenario: Allow-list for Claude Code
- **WHEN** the tool is `claude`, the mode is the allow-list with the rules `Read`, `Edit` and `Bash(git *)` and the deny rule `Bash(curl *)`
- **THEN** the script passes one `--allowedTools` argument with the three rules and one `--disallowedTools` argument with the deny rule, and no blanket permission

#### Scenario: Blanket permission for Claude Code
- **WHEN** the tool is `claude` and the mode is `allow-all-tools`
- **THEN** the script passes `--permission-mode bypassPermissions`, and a headless engine starts only when that mode was set explicitly

#### Scenario: Ask in a window
- **WHEN** the tool is `claude`, the runner is the window and the mode is `ask`
- **THEN** no permission argument is passed

#### Scenario: Neutral names
- **WHEN** `SUBSCRIBER_PERMISSION_MODE=allow-list` and `SUBSCRIBER_ALLOWED_TOOLS` are set, and the `SUBSCRIBER_COPILOT_*` names are not
- **THEN** the engine uses them, and when both are set the neutral name wins

### Requirement: The console and the checks follow the tool
`config` SHALL show the selected tool and its command, and `doctor` SHALL check that the selected tool's command can be found, not the other one's.

#### Scenario: Doctor with Claude Code
- **WHEN** the tool is `claude` and the Claude Code command is on the PATH while the Copilot command is not
- **THEN** `doctor` reports the command check as passed
