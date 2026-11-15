# The kit: agents, skills and tools

Agents, skills, custom instructions, prompt files, a planning rulebook and single-file tools that make
GitHub Copilot do modernization **your way** – and always through **GitHub Copilot app modernization**.

Install on Windows – one command checks the machine, installs only what is missing (Git, a portable JDK 25 and, for GitHub Copilot only,
PowerShell 7, the Copilot CLI through WinGet, the app-modernization plugin and the Node.js 22 that plugin needs) and copies the kit.
**The kit itself needs one runtime, a JDK 25, and no Node:** the tools, the installer and the audit validators are single-file Java.
With `--tool claude` nothing of the Copilot side (and no Node) is installed:

```
appmod init C:\src\my-repo [--tool copilot|claude|both] [--installer winget|npm] [--with-mcp] [--yes]
appmod doctor C:\src\my-repo        # check only
appmod upgrade plugin                # update the app-modernization plugin
appmod --help                        # all commands
```

Kit files only (any OS, JDK 25):

```
java install/KitInstaller.java --target C:\src\my-repo [--with-mcp] [--dry-run] [--force]
java install/KitInstaller.java --user          # agents + skills + tools into ~/.copilot for all repos
java install/KitInstaller.java --target C:\src\my-repo --tool claude   # for Claude Code (or --tool both)
```

**GitHub Copilot or Claude Code.** `--tool copilot` (the default, and what the installer always did), `--tool claude` or `--tool both`. The
`.github` files are the only source; for Claude Code the installer renders them: `.claude/agents/<name>.md` (Copilot's tool aliases mapped to
Claude Code's `Read, Grep, Glob, Edit, Write, Bash, PowerShell, Agent`; the Copilot-only `user-invocable` key dropped; paths in the text pointing
to `.claude/`), `.claude/skills/`, `.claude/rules/` (the instruction files) and a marked block in `CLAUDE.md`. `--with-mcp` also writes
`.mcp.json`; `--user --tool claude` fills `~/.claude`. The Java tools (`.github/appmod/tools`) and the rulebook are installed in the same
place for either tool. What Claude Code does **not** get: the front door `appmod-harness` (it delegates to the Copilot-only plugin
`github-copilot-modernization:modernize`; the installer says so) and the prompt files (they use VS Code input variables). So with Claude Code
use the agents directly: `claude --agent security-audit`, then `claude --agent security-fixer`. With `--tool both` the skills exist twice
(`.github/skills` and `.claude/skills`); the real Copilot CLI lists each skill and each agent once. A repository install writes `.appmod/kit.json` (the tool and a SHA-256 per placed
file): `--upgrade` (or `appmod upgrade kit <dir>`) then brings the kit files up to date and keeps any file you edited (how to update the kit, the Copilot plugin, the engine and the intel-service: [docs/updating.md](docs/updating.md)). `appmod kit <dir> --tool claude` and `appmod init` take
the same `--tool` option and ask on a new install. Tests: `java tests/installer/InstallerTests.java`.

Then use **one entry point** for everything:

```
copilot --agent=appmod-harness
> upgrade this app to Java 21 and Spring Boot 3.5         → GitHub Copilot app modernization (modernize)
> which dependencies are vulnerable, what to upgrade to?   → dependency-security-advisor (internal)
> assess db/ for Azure SQL                                 → sqlserver-modernizer (internal)
> security-audit this repository                           → security-audit (CSV of issues, read-only)
> fix the issues the audit found                           → security-fixer (changes code, in a git worktree)
```

or, in VS Code agent mode, `/modernize-java`, `/fix-cves`, `/dependency-advisor`, `/sqlserver-modernize`, `/code-graph`
(all run on `appmod-harness`), `/security-audit` (runs on `security-audit`) and `/security-fix` (runs on `security-fixer`, which changes code).

| Path | What |
|---|---|
| `.github/instructions/` | Ground rules (`applyTo: **`) and T-SQL rules |
| `.github/modernize/rulebook/` | Policies for app modernization's planner (targets, security, OpenRewrite-first) |
| `.github/agents/` | `appmod-harness` (the front door), `security-audit` and `security-fixer` (both user-invocable; `appmod-harness` routes audit and fix requests to them) + internal `appmod-verifier`, `appmod-openrewrite`, `dependency-security-advisor`, `sqlserver-modernizer` |
| `.github/skills/` | `openrewrite-recipes`, `code-knowledge-graph`, `dependency-security-advisor`, `tsql-migration`, `harness-reporting`, `security-audit` (Cloudflare's, vendored and pinned), `security-audit-csv` |
| `.github/prompts/` | VS Code prompt files |
| `.github/appmod/tools/` | `CodeGraph.java`, `DependencyAdvisor.java`, `FindingsToCsv.java`, `IssueList.java`, `ValidateRun.java` (run with `java <file> …`, JDK 25), `rewrite-init.gradle` |
| `.vscode/mcp.json`, `copilot-cli/mcp-config.json` | App-modernization MCP server configuration |

## Security audit

Ask `appmod-harness` for a security audit, run `/security-audit`, or start `copilot --agent=security-audit`. The
`security-audit` agent runs Cloudflare's security-audit skill (pinned in `.github/skills/security-audit/UPSTREAM.md`) on the
repository and writes **`.appmod/security-audit/issues.csv`**: one row per code location, with `file`, `line` and `title` as the
minimum, then fingerprint, verdict, severity, confidence, description, root cause, trace, remediation, attack class and the
audited commit. It is read-only: it never changes, commits or pushes the audited code, and it never fixes the issues.

- `confirmed` issues were demonstrated and have a severity; `needs_validation` issues are unproven (a specific fact is
  missing) and have **no** severity. Without an OS sandbox the audit runs no code from the repository, so expect many
  `needs_validation` rows. `rejected` candidates are not listed.
- Before the CSV is written, every cited file and line is checked against the repository (the file exists and the line is
  within it); if any are not, no CSV is written and the agent must correct them. This proves the location exists, not that
  it is exactly the flawed line, so a reviewer still reads the cited line.
- The skill's own run directory (findings, reports, working files) is written **outside** the repository, as the skill
  requires (default `~/security-audit-skill/<repo>/run-<N>`); `.appmod/result.json` records the path.
- **Handle the CSV like source code.** Its descriptions and location notes can quote code, and may quote a secret the
  audit found. `.appmod/` is excluded from the harness's commits, but add it to `.gitignore` if you work by hand.
- `java .github/appmod/tools/ValidateRun.java <run-dir>` runs the skill's two validators (`ValidateFindings.java`, `ValidateCoverageLedger.java`, in the
  skill folder). They are Java ports of the vendored Node validators (see `UPSTREAM.md`): same rules, limits, messages and exit codes, checked against the
  originals on 4,914 generated inputs and 92 edge inputs with no difference. They open a file without following links on every operating system, so there is no
  reduced mode on Windows any more (the Node originals could not read files there).
- Tests (the `tests/` folder is not installed into repositories), all from this folder, all `java <file>`:
  `tests/security-audit/ToolTests.java` (the CSV tool, `ValidateRun`, every tool starts as a single source file),
  `tests/security-audit/ValidatorTests.java` (the validators: 517 cases recorded from the originals, the command line, the helpers),
  `tests/security-fixer/ToolTests.java`, `tests/installer/InstallerTests.java`.

## Security fixer

**`security-fixer` changes code and runs the project's build and tests.** Start it with `/security-fix` (the path of the audit's CSV is the input), with
`copilot --agent=security-fixer`, or ask `appmod-harness` to fix what the audit found. The subscriber-engine starts it for every report published on
the audit subject.

- It reads the CSV with `java .github/appmod/tools/IssueList.java <csv>` (issues grouped by `fingerprint`, confirmed before unproven, most
  severe first) and fixes the `confirmed` issues in that order: one commit per issue (`fix(security): <title> [<fingerprint>]`), a regression
  test where the code allows, build and tests after each fix. `needs_validation` issues are changed only when the agent can show from
  the code that they are real.
- **It works in a git worktree**, not in your checkout: the subscriber-engine makes one for each session; started by hand in a
  checkout, the agent makes its own (`<repository>-fix-<id8>` on the branch `appmod/fix-<id8>`) and tells you where. Ask it to "work in place" only if you
  want your checkout changed. It never pushes; review with `git log` and `git diff` on the branch and merge it yourself. Remove the worktree with
  `git worktree remove --force <folder>` and `git branch -D <branch>` when you are done (look at `git status --short` in it first: `--force` drops anything
  uncommitted, and the agent's own `.appmod/` files are untracked).
- Interactive, it asks before changing a public contract, adding a dependency, touching the test setup or choosing between fixes
  of different risk. Headless, it takes the conservative option and records it under `assumptions`.
- Its report (`.appmod/result.json`, `.appmod/summary.md`) lists `issuesFixed` and `issuesLeft` with the reasons; the final build and test result comes
  from `appmod-verifier`.
- A new worktree has no installed dependencies, so the first build in it is slow and may need network.

Full guide: [docs/install-and-appmod-cmd.md](docs/install-and-appmod-cmd.md); also in `docs/`: custom instructions, the dependency advisor, the code knowledge graph and OpenRewrite.
