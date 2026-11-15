# Setting up a machine, and watching a fix

From a fresh clone to a finished fix session, with GitHub Copilot or Claude Code as the coding agent.

```
worktree-vuln-fixer/
  kit/   the KIT: agents, skills, tools, appmod.cmd
  engine/                                                      the ENGINE: NATS -> worktree -> agent window
  intel-service/                                               the vulnerability feed: daily CVE intelligence for the audit (MCP)
```

The flow: **audit** (an agent in your app repository) -> **publish** the issues file to NATS -> the **engine** makes a git worktree and
opens a Copilot or Claude Code window to fix the issues -> you **review** the branch.

## 1. Prerequisites

- Git.
- JDK 25 or later: the engine, the kit's tools and the validators all run on it. It is the only runtime of the kit and the engine.
- A NATS server the engine can reach.
- Windows PowerShell (the window runner uses it).
- The coding agent, signed in: Copilot CLI and/or Claude Code.

`appmod init` (step 3) checks the machine and installs what is missing on Windows: Git, a portable JDK 25 and, **for GitHub Copilot only**, PowerShell 7, the Copilot CLI
(WinGet) and the app-modernization plugin together with the Node.js 22 that plugin needs (its hooks run with `node` and its MCP server with `npx`; this is the one place
Node is used). With `--tool claude` none of the Copilot parts and no Node is installed. It does not install NATS.

## 2. Clone the project

```
git clone https://github.com/DhawanRachakonda/worktree-vuln-fixer.git C:\Projects\worktree-vuln-fixer
```

## 3. Install the kit into the repository you want to modernize

`appmod.cmd` is in `kit\`.

```
cd C:\Projects\worktree-vuln-fixer\kit
appmod init C:\src\my-repo            # checks the machine, installs the kit; asks copilot / claude / both on a new install
appmod doctor C:\src\my-repo          # check only
```

Kit files only, on any OS with JDK 21:

```
java install/KitInstaller.java --target C:\src\my-repo --tool claude    # copilot (default), claude or both
```

This writes `.github/...` for Copilot, `.claude/...` for Claude Code, the Java tools and a `.appmod/kit.json` record.
`appmod upgrade kit C:\src\my-repo` brings the kit files up to date and keeps any file you edited.

## 4. Run the audit

In the app repository:

- Copilot: `copilot --agent=appmod-harness`, or `copilot --agent security-audit`
- Claude Code: `claude --agent security-audit` (there is no `appmod-harness` for Claude Code)

The report is written to `<repo>\.appmod\security-audit\issues.csv`.

## 5. Start NATS

Note its URL. The engine's default is `nats://localhost:4222`; change it with `NATS_URL`.

## 6. Build and start the engine

```
cd C:\Projects\worktree-vuln-fixer\engine
gradlew.bat build
set SUBSCRIBER_ALLOWED_ROOTS=C:/src
set SUBSCRIBER_AGENT_TOOL=claude             rem or leave unset for copilot
set SUBSCRIBER_PERMISSION_MODE=allow-list    rem plus SUBSCRIBER_ALLOWED_TOOLS / SUBSCRIBER_DENIED_TOOLS
java -jar build\libs\subscriber-engine-0.1.0-SNAPSHOT.jar
```

- `SUBSCRIBER_ALLOWED_ROOTS` is required: the report and the repository must lie inside it.
- Start it from a normal terminal; the `$>` prompt of the console appears. Under `bootRun`, a service or a script there is no prompt.
- Run `doctor` first. It checks the tool command, NATS, Git and the roots.

## 7. Publish the report

In the engine's console (the `$>` prompt):

```
$> publish C:\src\my-repo\.appmod\security-audit\issues.csv
```

It uses the engine's own NATS settings, checks that the file exists, and tells you what it published. From another machine use the NATS
CLI: `nats pub securityaudit.issuefixesAgent "C:/src/my-repo/.appmod/security-audit/issues.csv"`.

## 8. Watch it work

There are two places to look.

**The agent window.** A separate terminal window that the engine opens for each session. It runs Copilot or Claude Code in the session's
worktree. Here you see the agent read the report, edit files and commit, and you can type to it. When the agent ends the window closes;
with Claude Code, leave it with `/exit`.

**The console** (the `$>` prompt in the terminal where you started the engine). It shows what the engine recorded, not the conversation:

| Command | What it shows |
|---|---|
| `sessions` | All sessions with their state: `QUEUED`, `RUNNING`, `DONE`, `PARTIAL`, `NEEDS_INPUT`, `ERROR` |
| `session <id>` | One session: worktree, branch, commits, exit code, outcome |
| `events` | The timeline: received, queued, worktree created, started, ended |
| `config` | The tool and settings in force |
| `doctor` | Checks the tool command, NATS, Git and the roots |
| `reopen` | Opens a window that resumes a finished session's conversation |
| `cleanup` | Removes the worktrees of finished sessions |
| `exit` | Stops the engine |

A typical watch:

1. Publish the report (step 7).
2. In the console run `sessions`: the new session is `QUEUED`, then `RUNNING`.
3. Switch to the agent window that opened and watch it work.
4. Run `sessions` or `session <id>` again: the state is `DONE` (or `PARTIAL`, `NEEDS_INPUT`, `ERROR`) with the commit count.
5. Check the result in the worktree (`<repo>-fix-<id8>`, branch `appmod/fix-<id8>`) with `git log`.

Without the console: `GET http://127.0.0.1:8080/api/sessions` returns the sessions as JSON; the engine log is `<data dir>/engine.log`; the
session folder in the data directory holds the prompt and `exit.json`. If the window does not open, run `doctor` and `events` first.

## 9. Review and clean up

- Inspect or merge the `appmod/fix-<id8>` branch.
- Run `reopen` (on its own line, it asks which session) or `reopen <id>` to talk to the agent about a finished session. It uses the tool
  recorded for that session, whatever the setting is now.
- Run `cleanup` to remove finished worktrees; `exit` stops the engine.

## Choosing Copilot or Claude Code

The choice is made in two places, and they should agree:

- **The kit:** `--tool` at install decides which agent files exist in the repository.
- **The engine:** `SUBSCRIBER_AGENT_TOOL` at start decides which command the engine launches.

A kit with only `.github` files and an engine set to `claude` has no `.claude` agents to run. For a demo of both from one repository install
with `--tool both` and switch the engine setting. See [operations.md](operations.md#choosing-the-coding-agent-copilot-or-claude-code).

## Cautions

- Use `allow-list` as the permission mode. `allow-all-tools` (Claude Code: `bypassPermissions`) was not run for real, and a headless engine
  refuses it unless it is set on purpose.
- Keep `SUBSCRIBER_ALLOWED_ROOTS` narrow and point the engine at test repositories while demoing.
- With Claude Code, start the engine from a normal terminal if you can; the scripts clear an inherited Claude Code session either way.
