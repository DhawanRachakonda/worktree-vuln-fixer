`# Design
`
`## Context
`
`Every launch goes through `LaunchPlan` (validated values only) and `LauncherScripts`, which fills one of four templates (`window.ps1`, `headless.ps1`, `headless.sh`,
``reopen.ps1`) whose command line is Copilot's: `--agent`, `--session-id`, `--name`, `--allow-all-tools` / `--allow-tool` / `--deny-tool`, `--no-ask-user`, `--add-dir`,
``--log-dir`, `--share`, `-p` or `-i`, and `--resume` for reopen. The three launchers and `SessionReopener` read `properties.copilot()`; `IntakeService` records the engine as
`the constant `copilot`. Claude Code (2.1) takes `--agent`, `--session-id <uuid>`, `--name`, `--add-dir`, `-p` with the prompt as a positional argument, `--output-format`,
``--permission-mode`, `--allowedTools`, `--disallowedTools` and `--resume`; it has no `--log-dir`, `--share` or `--no-ask-user`.
`
`## Goals / Non-Goals
`
`**Goals:** one setting chooses the tool; everything downstream of the script (exit record, states, worktrees, console, API, recovery) is unchanged.
`**Non-Goals:** a per-subject tool, mixing both tools in one engine, renaming the `subscriber.copilot.*` settings, a Claude Code version of the front door agent.
`
`## Decisions
`
`- **A separate set of templates for Claude Code, not conditionals inside the Copilot ones.** The two command lines share almost nothing but the session id and the agent
`  name, and the Copilot goldens and tests stay byte-identical. `LauncherScripts` picks the template by tool and flavor.
`- **`LaunchPlan` carries the tool** (a new last component with a convenience constructor that defaults to Copilot), so every existing plan and test keeps meaning Copilot.
`- **Permission settings stay under `subscriber.copilot.*`** (renaming would break every existing deployment) and get neutral environment aliases through a nested placeholder
`  (`${SUBSCRIBER_PERMISSION_MODE:${SUBSCRIBER_COPILOT_PERMISSION_MODE:}}`); the headless guard reads the same property path, so "explicitly set" still works for either name.
`  Rules are passed through in the tool's own syntax: the validation (a plain character set, no quote, no comma) is the same for both and is what keeps a rule from becoming a command.
`- **One argument for the Claude Code tool lists.** `--allowedTools` and `--disallowedTools` are variadic, so a prompt placed after them would be swallowed (found when running Claude Code by hand).
`  The rules are joined with commas into one quoted argument, and the prompt is the first positional argument, before the options.
`- **The headless run is kept by the engine's own output log** (stream-json on stdout), because Claude Code has no `--log-dir` or `--share`.
`- **The window ends when the person leaves Claude Code**, as for Copilot: an interactive session has no other end. With a permission rule set that lets the agent finish, the
`  window stays open at the prompt until `/exit`.
`- **`bypassPermissions` for `allow-all-tools`.** It is Claude Code's equivalent and it is as dangerous as the Copilot flag; the headless guard already refuses it unless it was set on purpose.
`- **The scripts clear the variables of a parent Claude Code session.** Found by the first real window run: the engine had been started from a Claude Code session, the window's
  `claude` inherited `CLAUDECODE`, `CLAUDE_CODE_CHILD_SESSION`, `CLAUDE_CODE_SESSION_ID` and the like, behaved as that session's child, and saved no conversation file, so `reopen` answered "no conversation found".
  Started again from an environment without them, the same run saved its file and `reopen` resumed it. Clearing them in the script (a short loop in PowerShell, `unset` in sh) makes
  the demo work whoever starts the engine, including an operator inside Claude Code. Only variables that name a session are cleared; credentials and settings are not.
- **`reopen` uses the recorded engine.** A session started with one tool cannot be resumed by the other (the conversation lives in that tool's own store), so the setting at reopen time is irrelevant.
`
`## Risks / Trade-offs
`
`- Claude Code writes its conversation under the working folder's project key, so `--resume` must run in the same folder the session ran in (the worktree): the same rule the engine already follows for Copilot.
`- The Claude Code flags are those of version 2.1.x; a later version may rename one, and the golden files would not notice. The real run (headless) is what proves them.
`- The stand-in used by the launcher tests records the arguments; it proves what the engine passes, not what Claude Code does with it. The real runs do.
