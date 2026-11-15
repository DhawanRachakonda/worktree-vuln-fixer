# Tasks

## 1. Installer

- [x] 1.1 Add `--tool copilot|claude|both` (default `copilot`, any other value refused before anything is written) and refactor the file writing so
      generated content and copied files follow the same rules (unchanged, kept unless `--force`, dry run); verify that the default run
      places exactly the files it placed before, byte for byte
- [x] 1.2 Render the Claude Code agents: tool mapping table, `user-invocable` dropped, unknown tool refused with the agent's name, path rewrite,
      `appmod-harness` skipped with a report line; verify against `security-fixer`, a `user-invocable: false` agent, and a fixture agent with an unknown tool
- [x] 1.3 Place Claude Code skills (path rewrite in text files only), rules (`applyTo` to `paths`), the `CLAUDE.md` block (merged once) and `.mcp.json`
      with `--with-mcp`; keep `.github/appmod/tools` and `.github/modernize/rulebook` for both tools; verify no generated file refers to the three
      `.github` prefixes
- [x] 1.4 `--user` with Claude Code into `~/.claude`; verify with `-Duser.home` pointing at a temporary folder

- [x] 1.5 Record the install in `.appmod/kit.json` and add `--upgrade` (only unedited files, recorded tool, refusals); verify tests for the record, an upgrade with an
      edited file, a second upgrade, `--force`, no record, a different tool and `--user`, and that a broken upgrade (one that overwrites edited files) fails them
- [x] 1.6 `appmod.cmd`: `--tool`, the question on a new install, `upgrade kit` through `--upgrade`, a Claude Code line in the check and the closing hint; verify by running
      `appmod kit` and `appmod upgrade kit` on temporary repositories (explicit tool, `--yes`, the question answered through a pipe, no record)
- [x] 1.7 `validate-run.cjs` finds the skill in `.github/skills` or `.claude/skills`; verify with a Claude-only install and `validate-run.cjs` without `--skill-dir`

## 2. Tests and checks

- [x] 2.1 Add `tests/installer/InstallerTests.java` (single file, JDK only, runs the installer as a separate process into temporary folders) covering every
      scenario of the spec: default, unknown tool, both, tool mapping, unknown tool name, front door left out, skills and rules, existing `CLAUDE.md`,
      stale paths, second run, an edited file, dry run, MCP, user install; verify it passes and that it fails when the tool mapping is broken
- [x] 2.2 List the generated agents with the `claude` CLI in a target folder and record what it shows (`claude agents` lists running sessions, so the listing came from the error of `claude --agent no-such-agent`); a failure to list them is a finding, not
      a pass

- [x] 2.3 Run the generated agents for real in Claude Code (installer output, `--tool claude`, an explicit tool allow-list, no blanket permission):
      `security-fixer` on the fixture (checker: 37 of 37) and `security-audit` on the audit fixture (answer key); record both in `tests/installer/spec-coverage.md`;
      what the runs found is fixed (the validator's skill folder)
- [x] 2.4 Ask the real Copilot CLI what it sees after `--tool both` (skills once each; agents once each, from `.claude`) and record it

- [x] 2.5 Run the interactive question path of `security-fixer` in Claude Code on a fixture that forces a decision (a public JSON shape): asked before acting, `needs_input`
      report, resumed with the answer, one commit and tests green; record it in `tests/security-fixer/acceptance.md` (a first try on another fixture asked nothing and is recorded as such)

## 3. Documentation

- [x] 3.1 Update the kit `README.md` (the option, what is generated, what is skipped and why, the duplicated-skills note), add
      `tests/installer/spec-coverage.md` (every scenario and its proof, and the gaps); verify `openspec validate dual-tool-kit --strict` passes
