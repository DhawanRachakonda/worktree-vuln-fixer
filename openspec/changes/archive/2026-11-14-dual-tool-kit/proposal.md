# Proposal

## Why

The Markdown kit (`copilot-mordernize-harness/appmod/offering-1-markdown-kit`) installs agents, skills, instructions and prompts only
in the places GitHub Copilot reads (`.github/...`). The security-audit and security-fixer agents do not depend on Copilot at all, but a
team that works with Claude Code cannot use them without copying and editing the files by hand, and two hand-kept copies would drift.
The installer already is the one place that decides where files go, so it should also be the place that renders them for Claude Code,
from the same sources.

## What Changes

- `KitInstaller` gets `--tool copilot|claude|both`. The default is `copilot`, which does exactly what the installer does today.
- For Claude Code (`claude`, or both) a repository install **generates** from the kit's existing files, without changing them:
  - `.claude/agents/<name>.md` from each `.github/agents/<name>.agent.md`: the `tools` list is mapped to Claude's tool names
    (`read` to `Read`, `search` to `Grep, Glob`, `edit` to `Edit, Write`, `shell` to `Bash, PowerShell`, `agent` to `Agent`), the Copilot-only
    `user-invocable` key is dropped, and paths in the text point to the Claude locations.
  - `.claude/skills/<name>/...` as copies of the skills (the `SKILL.md` format is the same in both tools), with the same path rewrite in text files.
  - `.claude/rules/<name>.md` from the instruction files (`applyTo` becomes `paths`), and a marked block in `CLAUDE.md` (merged, never overwritten) that
    replaces `copilot-instructions.md` for Claude Code.
  - `.mcp.json` for the app-modernization MCP server with `--with-mcp`.
- The tool-neutral support files stay where they are, for either tool: `.github/appmod/tools/` (the Java tools the agents run) and
  `.github/modernize/rulebook/`.
- The front door `appmod-harness` is **not** installed for Claude Code: it delegates to the GitHub Copilot-only plugin
  `github-copilot-modernization:modernize`. The installer says so in its report. The prompt files are not converted either (they use VS Code
  input variables). Both are stated gaps, not silent omissions.
- A repository install writes `.appmod/kit.json` (the tool and the SHA-256 of every placed file), and `--upgrade` uses it: newer kit files replace only files nobody
  edited. `appmod.cmd` gets `--tool`, asks which tool on a new install, and `appmod upgrade kit` uses `--upgrade`.
- The kit's `validate-run.cjs` finds the vendored skill in `.github/skills` or `.claude/skills` (a real Claude Code audit run found that it looked only in the first).
- `--user` installs for Claude Code into `~/.claude` (agents and skills) the way it installs for Copilot into `~/.copilot`.
- The installer keeps its rules for both tools: identical files are "unchanged", differing files are kept unless `--force`, `--dry-run`
  writes nothing, and a second run changes nothing.

## Capabilities

### New Capabilities
- `kit-install-targets`: installing the Markdown kit for GitHub Copilot, Claude Code or both, rendered from one set of sources.

### Modified Capabilities

None.

## Impact

- `copilot-mordernize-harness/appmod/offering-1-markdown-kit/install/KitInstaller.java`, a new test file `tests/installer/InstallerTests.java`, the kit
  `README.md`.
- No change to any agent, skill, prompt or instruction file, so every Copilot install and the kit's existing tests are unaffected.
- `appmod.cmd` (the `--tool` option, the question, `upgrade kit`) and the Java tool `validate-run.cjs`.
