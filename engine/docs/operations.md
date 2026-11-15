# Operating the engine

Day-to-day use of subscriber-engine: starting it, what each session state means, what a restart does, where a session's files are,
how to reopen a finished session, and the operator console.

## Start and stop

```
SUBSCRIBER_ALLOWED_ROOTS=C:/src ./gradlew bootRun          # from engine/
java -jar build/libs/subscriber-engine-0.1.0-SNAPSHOT.jar   # after ./gradlew bootJar
```

In PowerShell set the variable first (`$env:SUBSCRIBER_ALLOWED_ROOTS = "C:/src"`). All settings are in the README. The engine stops
with Ctrl+C or, when the operator console is on, with `exit` in the console.

- **Allowed roots** (`SUBSCRIBER_ALLOWED_ROOTS`, required): a report and its repository must lie inside one of them, so a message
  cannot send the engine into a folder you did not name. A root must exist when the engine starts.
- **Permission mode** (`SUBSCRIBER_COPILOT_PERMISSION_MODE`): `allow-all-tools` (default) starts the agent with
  `--allow-all-tools`, so it does not stop to ask before each tool. With `ask` the person approves each tool in the window. Use
  `ask` when the repositories or reports are not yours. With `allow-list` the agent may use only what
  `SUBSCRIBER_COPILOT_ALLOWED_TOOLS` names, without asking, and never what `SUBSCRIBER_COPILOT_DENIED_TOOLS` names (deny wins).
  This is the mode for unattended runs, in the headless runner too, where no blanket permission is given. **Headless needs a decision:**
  the engine does not start with the headless runner when the mode is `ask` (nobody can answer) or when it was not set (the default
  `allow-all-tools` is not a decision); set `allow-list`, or `allow-all-tools` on purpose, which logs one warning at the start. Rules are the CLI's own
  (`copilot help permissions`), comma separated, and only letters, digits, space and `_ . : * ? / \ ( ) ~ -` are accepted. A rule
  given with another mode, or `allow-list` without an allow rule, stops the engine at start. A list for the `security-fixer` agent:

  ```powershell
  $env:SUBSCRIBER_COPILOT_PERMISSION_MODE = "allow-list"
  $env:SUBSCRIBER_COPILOT_ALLOWED_TOOLS = "write(C:/src/**),shell(git:*),shell(java:*),shell(Test-Path),shell(Join-Path),shell(Split-Path),shell(Resolve-Path),shell(Write-Error)"
  $env:SUBSCRIBER_COPILOT_DENIED_TOOLS = "shell(curl:*),shell(git push:*),shell(git filter-branch:*),shell(git rebase:*),shell(git update-ref:*)"
  ```

  `shell(git:*)` allows `git` with any subcommand; `shell(git)` alone allows only a bare `git`. That includes subcommands that rewrite
  history (a real run once used `git filter-branch -- --all` and rewrote the person's `master`), so deny the ones you do not want, as
  above. Allow `write` only for the folders
  the engine may use (the repository and its worktree folder). The model is not an engine setting: the CLI reads `COPILOT_MODEL`
  from the environment the engine was started in. `config` in the console lists the rules in force.
- One engine per data directory. A second one stops at start and names the directory.

## Where a session's files are

Everything for one session is in `<data dir>/sessions/<session id>/` (the data directory defaults to `./data`):

| Path | What it is |
|---|---|
| `report/<name>` | The copy of the report taken when the message was accepted. The agent works from this copy. |
| `prompt.md` | The task given to the agent: the report copy, the repository, and what to produce. |
| `run.ps1` or `run.sh` | The generated launcher script. The repository path is only a quoted literal inside it. |
| `exit.json` | Written by the launcher when the agent ends: exit code, end time, the repository's commit. |
| `logs/` | The agent's own logs (`--log-dir`) and the launcher output (`runner.log`). |
| `reopen.ps1`, `logs-reopened/` | Only after a `reopen`: the script and the agent logs of that window. |

The agent writes its own result to `.appmod/result.json` in the folder it works in: the session's worktree (see below), or the repository when
isolation is `none`. The engine reads it, together with `exit.json`, to decide the final state. The session folder is not deleted by the engine.

## Where the fixes are: the session's worktree

Unless isolation is `none` (see below), each session works in its **own git worktree**: a second folder on a new branch that shares the
repository's history. Your own checkout is never switched or changed, so you can keep working while the agent fixes.

| | |
|---|---|
| Name and folder | `<repository folder>-fix-<first 8 characters of the session id>`, a sibling of the repository inside an allowed root, for example `C:\src\shop-fix-2f6c0a1e` |
| Branch | `appmod/fix-<first 8 characters of the session id>` |
| Starts from | the commit the audit report names (a CSV whose `commit` cell holds a commit id that exists in the repository, alone or as the audit tool writes it, `commit=<id>, dirty=<true|false>`), otherwise `HEAD` at that moment. An event says when the report's commit could not be used and why, and when the audit ran on a modified tree (`dirty=true`) |
| Not included | your uncommitted changes: the worktree holds committed code only. Commit first if you want them in |
| Cold start | the worktree has no installed dependencies or build output, so the first build and test run in it is slow and may need network |

The session record keeps the worktree's **name, folder, branch, base commit and the number of commits the agent made** (counted when the
session ends, whatever state it ends in), and the timeline has an event when the worktree is created and one with the count at the end.

```
$> session 2f6c0a1e-5b7d-4c34-9a41-0d3b8e7f1a22
...
worktree              shop-fix-2f6c0a1e
worktree folder       C:\src\shop-fix-2f6c0a1e
branch                appmod/fix-2f6c0a1e
base commit           9f8e7d6c5b4a39281706f5e4d3c2b1a098765432
commits made          2

To review:   git -C "C:\src\shop" log 9f8e7d6c5b4a39281706f5e4d3c2b1a098765432..appmod/fix-2f6c0a1e
             git -C "C:\src\shop" diff 9f8e7d6c5b4a39281706f5e4d3c2b1a098765432..appmod/fix-2f6c0a1e
             git -C "C:\src\shop-fix-2f6c0a1e" status --short   (anything listed is not committed; cleanup refuses while there is any)
To remove the folder:  cleanup 2f6c0a1e-5b7d-4c34-9a41-0d3b8e7f1a22   (add --yes to do it; the branch and its commits stay)
To remove the branch:  git -C "C:\src\shop" branch -D appmod/fix-2f6c0a1e   (only after you have merged or no longer need the commits)
```

`sessions` has a `FIXES` column: the number of commits to review, or `-` for a session without a worktree or without commits. The read
API returns the same five fields (`workName`, `workPath`, `workBranch`, `baseCommit`, `fixCommits`).

**Merging.** The commands above are only printed; the engine never runs them. To take the fixes, merge the branch yourself
(`git merge appmod/fix-2f6c0a1e`, or cherry-pick single commits). When the base is an older audited commit and the code has moved on,
git tells you about conflicts at that point, which is the right place to settle them. By default the engine does not push either; to have the finished branch pushed (and a pull request opened by a command you choose) see [publishing.md](publishing.md).

**Removing.** The engine never deletes a worktree on its own, and never a branch: they are your review material. When you are done,
use the console command `cleanup <id>` (see the console table): without `--yes` it only says what it would remove; with `--yes` it
removes the worktree **folder** of a finished session and nothing else, so the branch and its commits stay. It first proves that git lists
the folder as a worktree of the session's repository, that the folder and branch have the form the engine made, and that nothing is
uncommitted except the agent's own `.appmod/` reports (`result.json`, `summary.md`, which go with the folder). It refuses for a session
that is unknown, not finished, without a worktree or whose folder is already gone, while another session is queued or running in the
same repository, and when the worktree holds uncommitted work: commit or discard that yourself, then ask again. On Windows a folder that
an editor or a window still holds can fail to go; the answer then carries git's reason and nothing is recorded. After a cleanup the
timeline has one `worktree removed` event, `session <id>` says the folder no longer exists, and `reopen` refuses the session. Delete
the branch yourself with the last command of the `session` output once you no longer need its commits.

**When a worktree cannot be created** (the folder would be outside the allowed roots, it already exists, git refuses, the repository
has no commit, or on Windows the path is too long), the session ends as `ERROR` with the reason (git's own error line), nothing is left behind from the attempt, and
the next session starts. **Long paths on Windows:** the engine refuses a worktree folder over 200 characters, and a repository whose `.git/worktrees/<name>`
folder would pass 230 (git keeps the worktree's own files there, and Windows stops at 260); put the repository in a shorter folder.

**Linux.** The engine runs and its tests pass there (Ubuntu under WSL 2, JDK 25). The runner is headless (`auto` means headless off Windows), so the engine starts only with
a permission decision: `SUBSCRIBER_COPILOT_PERMISSION_MODE=allow-list` with rules, or `allow-all-tools` on purpose.

**Isolation `none`.** `SUBSCRIBER_ISOLATION=none`, or `isolation: none` on one subject in `application.yml`, keeps the previous
behaviour: the agent works in the repository's own checkout and no worktree is recorded. Every other rule is unchanged: still one
session at a time per repository, because that rule looks at the repository, not at the worktree.

## Choosing the coding agent: Copilot or Claude Code

`SUBSCRIBER_AGENT_TOOL` is `copilot` (the default) or `claude`. Everything else is the same for both tools: the same sessions, worktrees, states, console and API, the same
agent (`security-fixer` from the kit installed for that tool, see the kit's `--tool`), and the same exit record. The tool is recorded on each session (`engine` in the API
and the database) and `reopen` resumes a session with the tool that ran it, even if the setting has changed since.

| | Copilot CLI | Claude Code |
|---|---|---|
| Command | `SUBSCRIBER_COPILOT_COMMAND` (`copilot`) | `SUBSCRIBER_CLAUDE_COMMAND` (`claude`) |
| Headless | `copilot --agent A --session-id ID -p "Read and follow ..." --no-ask-user --share ... --log-dir ...` | `claude -p "Read and follow ..." --agent A --session-id ID --output-format stream-json --verbose` (the run is kept in `logs/runner.log`) |
| Window | `copilot ... -i "Read and follow ..."` | `claude "Read and follow ..." --agent A --session-id ID` (ends when the person leaves Claude Code with `/exit`) |
| Reopen | `copilot --resume ID` | `claude --resume ID` |
| `allow-all-tools` | `--allow-all-tools` | `--permission-mode bypassPermissions` |
| `ask` | no argument | no argument |
| `allow-list` | `--allow-tool 'shell(git:*)'` and `--deny-tool '...'` | `--allowedTools 'Read,Edit,Bash(git *)'` and `--disallowedTools '...'` (one comma-joined argument each) |

The permission settings (`SUBSCRIBER_PERMISSION_MODE`, `SUBSCRIBER_ALLOWED_TOOLS`, `SUBSCRIBER_DENIED_TOOLS`, or the older `SUBSCRIBER_COPILOT_*` names; the neutral name wins) apply to either tool, and
the rules must be written in the syntax of the tool that runs. The headless guard applies to both: headless needs `allow-list`, or `allow-all-tools` set on purpose. An example for
Claude Code (PowerShell):

```powershell
$env:SUBSCRIBER_AGENT_TOOL = "claude"
$env:SUBSCRIBER_PERMISSION_MODE = "allow-list"
$env:SUBSCRIBER_ALLOWED_TOOLS = "Read,Edit,Write,Grep,Glob,Agent,Bash(git *),Bash(java *)"
$env:SUBSCRIBER_DENIED_TOOLS = "Bash(curl *),Bash(git push *),Bash(git filter-branch *),Bash(git rebase *),Bash(git update-ref *),WebFetch,WebSearch"
```

Claude Code keeps its conversations per working folder, so `reopen` works because it runs in the folder the session ran in (the worktree). The scripts also clear the environment variables that name a running Claude Code session (`CLAUDECODE`, `CLAUDE_CODE_SESSION_ID`, ...): a `claude` that inherits them from the session the engine was started in behaves as that session's child and saves no conversation, so `reopen` would find nothing. Credentials and settings are kept. `doctor` and `config` follow the selected tool.

## States

| State | Final | What it means and what to do |
|---|---|---|
| `QUEUED` | no | Accepted, waiting for a free slot (`SUBSCRIBER_MAX_PARALLEL`, and never two sessions in one repository). Nothing to do. |
| `IN_PROGRESS` | no | The window is open. The person can type in it. Closing it with Ctrl+C ends the session as `INTERRUPTED` or `ERROR`, never `DONE`. |
| `DONE` | yes | The agent ended cleanly and its fresh report says `succeeded`. |
| `PARTIAL` | yes | The report says `partially_succeeded`. Read the report, then `reopen` to continue. |
| `NEEDS_INPUT` | yes | The report says the agent was blocked on a decision. `reopen` and answer. |
| `ERROR` | yes | The agent failed, ended with no fresh report, or could not be started. `reason` says which. |
| `INTERRUPTED` | yes | The window was gone and left no exit record (for example the machine restarted). |
| `REJECTED` | yes | The message failed a check (path, roots, `.git`, duplicate). `reason` says which. No agent was started. |

A clean exit proves nothing: a person who quits the window with Ctrl+C gives exit code 0, so `DONE` needs the agent's own fresh
report.

## What a restart does

When the engine starts it settles what the last run left, before it accepts new work:

| Found | Result |
|---|---|
| `QUEUED` | Stays queued and runs in its original order. |
| `IN_PROGRESS`, `exit.json` exists | Finished from the record, as if the engine had been running. |
| `IN_PROGRESS`, window still open | Followed again until it ends. |
| `IN_PROGRESS`, window gone, no record | `INTERRUPTED`. |

**The limit:** NATS core keeps no messages. A report published while the engine is down or disconnected is **lost**; publish it
again. `doctor` (below) shows whether NATS is connected.

## Reopen a finished session

A finished session keeps its Copilot conversation, so a person can continue it by hand. Either use the console:

```
$> reopen 2f6c0a1e-5b7d-4c34-9a41-0d3b8e7f1a22
Opened a window that resumes session 2f6c0a1e-5b7d-4c34-9a41-0d3b8e7f1a22 in C:/src/shop. The session's state is unchanged.
```

or do it yourself, in the folder the session worked in (its worktree when it has one):

```
cd C:\src\shop-fix-2f6c0a1e
copilot --resume 2f6c0a1e-5b7d-4c34-9a41-0d3b8e7f1a22
```

The console opens the window in the session's worktree when it has one, and refuses when that worktree has been removed. It also
refuses when the session is unknown, is not finished (`QUEUED` or `IN_PROGRESS`), never started an agent (for example
`REJECTED`), its repository is gone, or another session is queued or running in the same repository. A reopened window is the
person's: the engine does not follow it and records nothing about it except one `reopened` event on the session's timeline, so the
state stays as it was. **While you work in a reopened window the engine can still start a queued session in that repository**:
reopen only when nothing is waiting for it.

## The operator console

A Spring Shell prompt inside the engine's own process, for looking at sessions without `curl`. It reads the same records as the API
and can publish a report to NATS (`publish`), reopen a session or, when asked with `--yes`, remove a finished session's worktree folder; it changes nothing else.

**When it runs.** `SUBSCRIBER_CONSOLE` (`subscriber.console.enabled`) is `auto` by default: the console starts only when the engine
is started from a terminal. Started by a service, a scheduler or with redirected input, it stays off and the engine runs exactly as
without a console. `false` turns it off always. `true` insists: the engine refuses to start when there is no terminal, instead of
silently running without one.

**Logs.** While the console is on, log lines go to `<data dir>/engine.log` and not to the terminal, so they do not tear the prompt.
With the console off they go to the terminal as usual.

**Starting it.** From a terminal, `java -jar build/libs/subscriber-engine-0.1.0-SNAPSHOT.jar` shows the prompt `$>`. **`./gradlew
bootRun` cannot host the console**: Gradle starts the engine without a terminal, so the console stays off there (and
`SUBSCRIBER_CONSOLE=true` stops the start with the "needs a terminal" message). Use the jar when you want the prompt.

| Command | What it shows |
|---|---|
| `help` | The commands. |
| `sessions [--state <STATE>]` | The 20 newest sessions, newest first, with the total; `--state ERROR` shows only that state. |
| `session <id>` | One session's record: state, subject, report, repository, agent, runner, times, exit code, git heads, outcome, reason. |
| `events <id>` | The states the session went through, oldest first. |
| `config` | The settings in force. A secret shows only as set or not set. |
| `doctor` | One line per check, pass or fail with the reason. |
| `publish <report path> [--subject <subject>] [--source-branch <branch>] [--unsafe-local-changes]` | Publishes the absolute path of a report file to NATS, on the first configured subject (the security-audit one) unless `--subject` names another configured one. `--source-branch` and `--unsafe-local-changes` (not both) are sent as the message headers `source-branch` and `unsafe-local-changes`; the console says which branch will be used before it publishes. See [publishing.md](publishing.md). |
| `publish-branch <id> [--yes]` | Pushes the branch of a finished session (and runs the pull-request command in `pull-request` mode); needed for a session that included local changes, and to retry a failed push. Without `--yes` it only says what it would do. |
| `reopen <id>` | Opens a window that resumes a finished session (see above). |
| `cleanup <id> [--yes]` | Removes the worktree folder of a finished session after `--yes` (the branch is kept); without it only says what it would do (see Removing). |
| `exit` | Leaves the console **and stops the engine**. |

```
$> sessions --state ERROR
ID                                    STATE        RECEIVED             SOURCE / REPOSITORY
2f6c0a1e-5b7d-4c34-9a41-0d3b8e7f1a22  ERROR        2026-11-10T12:00:00Z Security audit / C:/src/shop
Showing 1 of 1 ERROR sessions, newest first.

$> publish C:/src/shop/.appmod/security-audit/issues.csv
Published to securityaudit.issuefixesAgent: C:\src\shop\.appmod\security-audit\issues.csv

$> doctor
PASS  database              open
PASS  nats                  connected to nats://localhost:4222
PASS  allowed root          C:/src
PASS  copilot command       C:\Users\me\AppData\Roaming\npm\copilot.cmd
PASS  powershell            C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe
PASS  data directory        C:\engine\data is writable
All 6 checks passed.
```

Text that comes from a report, a repository or an agent is shown with control characters replaced by `?`, so it cannot move the
cursor or change the terminal.

## When something is wrong

Run `doctor` first. A failed line names the cause:

| Line | Usual cause |
|---|---|
| `nats` | The server is down or `NATS_URL` is wrong. The engine keeps trying; messages sent meanwhile are lost. |
| `allowed root` | A folder was moved or deleted after the engine started. Restart with a correct `SUBSCRIBER_ALLOWED_ROOTS`. |
| `copilot command` | Copilot CLI is not on the PATH of the process that started the engine, or `SUBSCRIBER_COPILOT_COMMAND` is wrong. |
| `powershell` | `powershell.exe` is not on the PATH (Windows; the window runner needs it). |
| `data directory` | The folder cannot be written; sessions cannot be recorded. |
