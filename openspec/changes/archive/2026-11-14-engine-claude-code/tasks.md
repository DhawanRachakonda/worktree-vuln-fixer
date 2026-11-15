# Tasks

## 1. Settings

- [x] 1.1 Add `subscriber.agent-tool` (`copilot`|`claude`, default `copilot`) and `subscriber.claude.command` (default `claude`, same validation as the Copilot command) with tool-neutral environment
      aliases for the permission settings in `application.yml`; verify `SubscriberPropertiesTest` (default, both values, an invalid value, the command validation) and `ContextLoadsTest` (the new names and the aliases,
      the neutral name winning, the headless guard still seeing an explicit mode set under either name)

## 2. Launching

- [x] 2.1 Add the tool to `LaunchPlan`, four Claude Code templates and the per-tool permission arguments in `LauncherScripts`; verify `LauncherScriptsTest` (every flavor, every permission mode, hostile rules refused,
      PowerShell and `sh -n` parse checks), new golden files for Claude Code, and that every Copilot golden is unchanged
- [x] 2.2 Make the three launchers and the reopen path pick the tool and its command; `reopen` uses the engine recorded for the session; verify `HeadlessLauncherTest`, `WindowLauncherTest` (opt-in) and `ReopenServiceTest`
      with a stand-in command that records its arguments (what Claude Code would be given, and the exit record)
- [x] 2.3 Record the tool as the session's engine in `IntakeService`; `config` and `doctor` follow the tool; verify `IntakeServiceTest`, `ConsoleCommandsTest` and `DoctorTest`

## 3. Real runs

- [x] 3.1 Run the engine headless with `claude` and an allow-list on the acceptance fixture, with the real Claude Code, and check it with the `security-fixer` checker (37 checks); record the session, the
      arguments Claude Code accepted, and anything the run showed
- [x] 3.2 Open a real window with `claude` (a person leaves it with `/exit`) and `reopen` it; record what was seen. Done with a person on 2026-11-14: the first try, with the engine started from inside a Claude Code
      session, saved no conversation and `reopen` found nothing; the cause was the inherited `CLAUDE_CODE_*` variables; after clearing them (by hand, then in the scripts) the window run
      was `DONE` with 5 commits and 37 of 37 checks, and `reopen` resumed the conversation and the agent answered what it had fixed
- [x] 3.3 Clear the variables that name a parent Claude Code session in the Claude Code scripts; verify `ClaudeLauncherScriptsTest`, the regenerated goldens and `ClaudeHeadlessLauncherTest` with a real launch
      (the stand-in sees none of the parent's variables and still sees the credential), and that removing the clearing makes the launch test fail

## 4. Documentation

- [x] 4.1 README settings table, `docs/operations.md`, `docs/connecting.md` and `docs/spec-coverage.md` (every scenario and its proof); verify `openspec validate engine-claude-code --strict` and `./gradlew build` pass
