# Design

## Context

See proposal.md for motivation. Observed facts that shape the approach:

- **The bug.** `MessageSubscriber.js` spawns Copilot with `stdio: ["pipe","inherit","inherit"]` and calls
  `child.stdin.end(prompt)`. Copilot starts interactively but its input is a closed pipe, so nothing can be typed. The tests
  assert that `stdio` value. The CLI offers `-i, --interactive <prompt>` (interactive, runs the prompt) and `-p, --prompt <text>`
  (non-interactive, exits when done); the code uses neither. This is inferred from the code and the CLI help, not yet reproduced.
- **Copilot CLI 1.0.108 flags used here** (from `copilot --help`): `--agent`, `--session-id <id>` (sets the UUID of a new
  session), `--name`, `--log-dir`, `--add-dir`, `--trust-folder`, `--allow-all-tools`, `--no-ask-user`, `-i`, `-p`, `--share`
  (only after a non-interactive run). On Windows the `copilot` command is a `.cmd` shim (and a `.ps1`), so arguments can be
  re-parsed by `cmd.exe`; the old code worked around this with an environment variable.
- **Spike findings (task 1.1, 2026-11-10, Windows PowerShell 5.1, Copilot CLI 1.0.108).** `--session-id` **cannot be combined with
  `--trust-folder`** (the CLI refuses), and `--trust-folder` applies only to a non-interactive prompt session, so neither runner
  uses it. In PowerShell `copilot` resolves to `copilot.ps1`, so no `cmd.exe` re-parse of its arguments happens inside the script.
  `-p` with `--agent --session-id --name --add-dir --log-dir` works (exit 0, logs written), and `copilot --resume <id>` and
  `--resume <name>` both reopen that session. A window started with `cmd /c start "t" /wait powershell -File run.ps1` opens,
  closes when the script exits, and `cmd.exe` waits for it, but **`cmd.exe` exits 0 whatever the script exits with** (script exit 7
  gave 0): only `exit.json` carries the real code. Copilot writes statistics to stderr, which PowerShell 5.1 turns into error records
  when redirected, so the script must not redirect stderr and must keep `$ErrorActionPreference = "Continue"`, relying on
  `$LASTEXITCODE`. **Interactive run with the person at the keyboard:** typing reached the agent (the saved session holds the two
  messages typed), the window closed by itself when the person pressed Ctrl+C, `exit.json` was written with the end HEAD, and
  **the exit code was 0 even though the person interrupted it**, so an interrupted session cannot be told from a clean one by the
  exit code; the state table's rule that exit 0 needs a fresh `result.json` is what classifies it (`ERROR`, "ended without a report").
- **The old contract.** Publisher: subject plus an absolute report path. Subscriber: one global queue, one hard-coded working
  directory, subjects `coverity.issuefixesAgent` and `bdh.issuefixesAgent`, core NATS (no persistence, no acknowledgements).
- **The agent's report.** Agents write `.appmod/result.json` (`status`: `succeeded`, `partially_succeeded`, `failed`,
  `needs_input`) and `.appmod/summary.md` in the repository (the `harness-reporting` skill); the security audit writes
  `.appmod/security-audit/issues.csv` there too.
- **Stack precedent.** `intel-service` already runs Java 25, Spring Boot 4.1.2, Gradle 9.9.0 (Kotlin DSL, wrapper), an
  embedded H2 file database (one process per file), Flyway with standard SQL only, and a loopback bind with a bearer token
  for anything wider. This change follows it. `nats-server` is not installed here; Docker is.

## Goals / Non-Goals

**Goals:**
- A person can type in the fix session; windows never pile up.
- Every session and its state is stored durably, and the engine recovers from a restart.
- Nothing a message or report contains can run as a command or widen what the agent may touch.
- A future web application can read everything without any change to the engine's storage.

**Non-Goals:**
- The web application, SSE and admin actions (cancel, retry); the schema leaves room, nothing more.
- JetStream or any delivery guarantee for messages published while the engine is down.
- The `security-fixer` agent, the audit-then-publish step, the Claude engine.
- Non-Windows window support (only the headless runner is offered there).

## Decisions

### D1. A Spring Boot application, `subscriber-engine/`, owns the database
Gradle Kotlin DSL, wrapper, Java 25 toolchain, Spring Boot 4.1.2 BOM; starters for web MVC (read API), JDBC, Flyway,
actuator, validation; runtime H2; the NATS Java client (`io.nats:jnats`, release 2.26.4 seen on Maven Central on 2026-11-10,
re-check at build time); GA versions only. Typed records for configuration, rows and API bodies; JSON with Jackson through
typed records, no tree traversal. Package `dev.subscriber`. No Spring AI.
*Alternatives (decided with the user):* keep Node and add a Java helper, Node with SQLite, or import into H2 only when a web
app exists. Rejected: an H2 file allows one process, and the engine must be that process.

### D2. Intake is cheap and never launches
```
 jnats dispatcher thread:  validate -> insert fix_session (QUEUED or REJECTED) -> return
 dispatcher loop thread:   pick oldest QUEUED when capacity is free -> runner.start
 watcher per session:      wait for end -> finalize state -> free capacity
```
`IssueMessageSource` is an interface with the jnats implementation behind it; tests use a fake. The NATS callback does no
launching, so a slow session never blocks the connection. Core NATS only: a message published while the engine is down is
lost (documented; JetStream is the later fix).

### D3. Subjects are typed configuration
`subscriber.subjects` is a list of records `{subject, source, agent, repository, report-location}`. The audit subject uses
`report-location: .appmod/security-audit/issues.csv`, so the repository is the third parent of the report and must contain a `.git`
entry; the other two subjects name a fixed `repository`. `agent` defaults to `appmod-harness`. Adding a subject or changing its
agent is configuration only, which is what lets `securityaudit.issuefixesAgent` point at `security-fixer` later.

### D4. Allowed roots, checked on real paths
`subscriber.allowed-roots` is required (startup fails without it, naming the setting). Both the report and the repository
are resolved with `toRealPath()` and must lie inside a root, which defeats `..` segments, junctions and symlinks. The report
must be a regular file; the repository must be a directory with `.git`. A duplicate is a message whose real report path already
has a `QUEUED` or `IN_PROGRESS` row.

### D5. Untrusted text never reaches a command line
Windows launchers re-parse arguments through `cmd.exe`, so a report path containing `&`, `%` or `^` would be dangerous. The
design keeps every untrusted value out of the command line:

```
 <data-dir>/sessions/<uuid>/
     report/<file>     snapshot copy of the report, taken when the message is accepted (the agent reads this copy,
                       so a new audit overwriting the original cannot change it mid-session)
     prompt.md         the task: source label, snapshot path, original path, treat-as-data rule, result.json contract
     run.ps1           launcher generated from a template with validated values only
     logs/             copilot --log-dir
     exit.json         written by run.ps1 when the agent ends
```
The command line contains only the session folder path, the uuid, the agent name and fixed text. The data directory is
validated at start to a conservative character set (letters, digits, `.`, `_`, `-`, `:`, `\`, `/`, space) and the agent name to
`[A-Za-z0-9_.:-]+`. The repository path appears only inside `run.ps1`, as a PowerShell single-quoted literal with `'` doubled.
The short prompt passed to the CLI is `Read and follow <session folder>\prompt.md`. `--add-dir` is the session folder (the
agent must read the snapshot and prompt, which are outside the repository).
*Alternative:* pass the prompt on the command line or in an environment variable. Rejected: quoting through `.cmd` shims and
PowerShell 5.1 is fragile, and the old code needed a special case for it.

### D6. The window runner (Windows)
```
 cmd.exe /c start "appmod fix <short id>" /wait powershell.exe -NoProfile -ExecutionPolicy Bypass -File <session>\run.ps1
```
`start` opens a new console window running the script and `/wait` keeps `cmd.exe` alive until the window closes. The Java
`Process` is that `cmd.exe`; its pid and start instant are stored as the liveness token (a pid alone is not enough, Windows reuses
them). `run.ps1`: sets the title, `Set-Location -LiteralPath '<repo>'`, runs
`copilot --agent <agent> --session-id <uuid> --name <name> <permission flags> --add-dir <session> --log-dir <session>\logs -i <short prompt>`
(no `--trust-folder`: it is for non-interactive sessions and conflicts with `--session-id`; an interactive session asks the person
to trust an unknown folder once, and the CLI remembers the answer),
captures `$LASTEXITCODE`, reads `git rev-parse HEAD`, writes `exit.json` atomically (temp file then rename) inside a
`try/finally` so Ctrl+C and errors still leave a record, then exits so the window closes. No `-NoExit`.
Permission flags come from configuration (`subscriber.copilot.permission-mode`): `allow-all-tools` (today's behaviour, the
default, because the repository is already inside an allowed root) or `ask` (the person approves in the window). `--no-ask-user` is
not passed in window mode, so the agent can ask the person.
*Alternatives:* inherit the subscriber's own console (Ctrl+C reaches both, logs interleave); Windows Terminal tabs (not
always installed). The exit code of `start /wait` is not relied on, because `exit.json` carries the agent's code.

### D7. The headless runner
`copilot --agent <agent> --session-id <uuid> --allow-all-tools --no-ask-user --add-dir <session> --log-dir <session>\logs -p <short prompt> --share <session>\transcript.md`
(no `--trust-folder`, for the same reason; a headless run in a folder the CLI does not trust yet is untested and listed as a risk)
(`--share` works after non-interactive runs), run through the same generated script (`run.ps1` on Windows with no window,
`run.sh` elsewhere), so `exit.json` is written the same way and recovery is identical. Selected by `subscriber.runner` or by
platform when it is `auto` (window on Windows, headless elsewhere).

### D8. States, written by the engine and finalized from facts
The engine writes `QUEUED` on acceptance, `REJECTED` on a failed check, `IN_PROGRESS` on launch (start time, pid, start HEAD from
`git rev-parse HEAD`, whether the tree was dirty). The final state is a pure function of three facts: `exit.json`, the agent's
`.appmod/result.json` and its modification time, and the liveness token.

| exit code | `result.json` newer than the start | State |
|---|---|---|
| 0 | `succeeded` | `DONE` |
| 0 | `partially_succeeded` | `PARTIAL` |
| 0 | `needs_input` | `NEEDS_INPUT` |
| 0 | missing, unreadable or older | `ERROR` ("ended without a report") |
| not 0, or no exit code | any, or `failed` | `ERROR` |
| none | none, window gone | `INTERRUPTED` |

The result file is read into a typed record (`status`, optional counts); unknown fields are ignored. A stale file from an earlier
session is ignored by comparing its time with the start. Each transition appends a `fix_session_event` row in the same
transaction as the state update.

### D9. Queue and concurrency
`subscriber.max-parallel` defaults to 1. The dispatcher also never starts two sessions in the same repository at once, even with
a larger limit. A session ending in any state frees its slot. On start the engine rebuilds its queue from the database:
`QUEUED` rows keep their `received_at` order.

### D10. Recovery
On start, for every non-final row: `QUEUED` is requeued; `IN_PROGRESS` with an `exit.json` is finalized (D8); with no `exit.json`,
a live liveness token (pid exists and its start instant matches) is re-attached by polling every 2 seconds for `exit.json` or the
process ending; otherwise `INTERRUPTED` with the reason. The poll is also how a re-attached session ends, since no `Process`
handle survives a restart.

### D11. Schema (Flyway V1, standard SQL only, as in intel-service)
`fix_session(id VARCHAR(36) pk, seq identity (arrival order), state, subject, source, report_path, snapshot_path,
repository_path, agent, runner, engine, received_at, started_at, ended_at, pid, pid_started_at, exit_code, head_start, head_end,
dirty_at_start, outcome, reason)`, `fix_session_event(id identity pk, session_id fk, occurred_at, state, detail)` with an index on
`(session_id, occurred_at)`, and a lookup table `fix_session_state(name)` holding the eight states, which both tables reference by
foreign key. The states are **not** a `CHECK (state IN (...))`: H2 2.4.240 builds a constant set for such a check inside the session
that creates it (Flyway's) and then fails every later connection with "The database has been closed" (found by the repository tests);
a lookup table is standard SQL and a test keeps it equal to the Java enum. The repository's state changes are guarded in SQL so a final
state is never changed again. Timestamps are UTC. H2 file `jdbc:h2:file:${subscriber.data-dir}/subscriber`; a failure analyzer turns the
file-lock error into "data directory <dir> is already in use" (the intel-service pattern). No JSON or array columns.

### D12. Read-only API
`GET /api/sessions?state=&page=&size=` (newest first), `GET /api/sessions/{id}`, `GET /api/sessions/{id}/events`. Typed response
records; no endpoint writes; no report content, only paths and reasons. Bound to `127.0.0.1`; any other `server.address`
requires `subscriber.access.token` (constant-time bearer check, rejected before any query), as in intel-service. Actuator
health reports NATS connection and database state.
A future web app, being a second process, uses this API (it cannot open the H2 file); SSE can be added on the same events.

### D13. NATS connection
URL from `NATS_URL` (default `nats://localhost:4222`); optional token, user and password, or credentials file, from
configuration, never logged; unlimited reconnect; one subscription per configured subject. Subjects are validated at startup
(no wildcards: the engine is not a general subscriber).

### D14. Publisher and the Node subscriber
`MessagePublisher.js` gains `securityaudit.issuefixesAgent` in its issue subject list with the same file check; its tests stay.
`MessageSubscriber.js`, its subscriber tests and the `subscribe` script are removed in the last task, after the Java engine has
passed acceptance; git history is the rollback.

### D15. Testing
- Unit tests: intake checks, real-path root checks (including a junction and `..`), repository derivation, state table,
  prompt and script generation with hostile values (`&`, `%`, `^`, `'`, spaces), schema migration, API paging and token.
- Integration tests with a fake message source and a stand-in `copilot` (a small script placed first on the PATH that records its
  arguments, reads `prompt.md`, writes `.appmod/result.json` and exits with a configurable code), through the headless runner.
- Window runner tests, Windows only and opt-in (`-Pwindow-tests`), with the same stand-in; they assert the window closes and
  `exit.json` appears.
- Live NATS test, opt-in with a NATS server (Docker is available) via `NATS_URL`.
- One manual acceptance run with the real Copilot CLI: a person types in the window, the window closes at the end, the
  state is recorded, and `copilot --resume <id>` reopens the conversation. Recorded in the change.

### D16. Operator console with Spring Shell, in the engine's process
The engine is a long-running process that an operator watches in a terminal, and it owns the only handle on the H2 file, so a console
that lives in the same process can read the repositories directly (a second process could not open the file). Spring Shell
(`spring-shell-starter` 4.0.3, the GA release on Maven Central, built on Boot 4.0.7; this project is on 4.1.2, so compatibility is
the first thing task 7.1 checks) provides the prompt, parsing and help; the commands are thin methods over the repositories:
`sessions [--state]`, `session <id>`, `events <id>`, `config` (secrets redacted by the records' own `toString`), `doctor`, `reopen <id>`.
- **Only when there is a terminal.** `subscriber.console.enabled` is `auto` (on when a terminal is attached), `true` or `false`. Without a
  terminal nothing starts, so running as a service is unchanged. `true` without a terminal stops the start with a message. `./gradlew bootRun` cannot host the console (Gradle starts the engine
  without a terminal, which was tried: passing standard input through gives the engine a pipe, not a terminal), so the console is used
  from `java -jar`.
- **Started after the application is ready, on its own thread, without arguments.** Spring Shell 4's own runner (an application runner)
  blocks while the prompt is open, before `ApplicationReadyEvent`, so recovery, dispatching and the NATS connection would wait for
  `exit`; it also takes Boot arguments such as `--server.port=9090` for commands. The engine supplies the runner bean Spring Shell looks
  for (a no-op) and starts the shell itself; when the shell ends the engine stops.
- **Logs move to a file while the console is on** (`<data dir>/engine.log`), so log lines do not break the prompt.
- **`reopen` is the only action.** It requires a final state and no queued or running session for the same repository, opens a window
  through the same safe launcher as D5/D6 running `copilot --resume <id>` in the repository (the repository path appears only inside
  the generated script as a quoted literal), and appends a "reopened" event. It does not change the state or take a queue slot: it is
  a person reading an old conversation, not a fix session.
- **Not exposed over HTTP.** The console and the read API are separate surfaces; the API stays read-only.
*Alternatives:* a separate CLI that calls the read API (works from another terminal, but cannot act on the machine's windows through the
engine's checks and needs the API running); a plain command loop on stdin (reimplements parsing and help).

### D17. A permission mode with an allow-list (added after the first real security-fixer run)
The two modes the engine had were everything (`--allow-all-tools`) and ask. Everything is more than a fix session needs and was refused by the
environment of the acceptance runs; ask cannot work unattended. A real run of the fixer showed that a narrow list works: writes inside
the working folders, `git`, `node`, `npm`, `java` with any subcommand, and `curl` and `git push` denied (deny rules win over every allow).
- **Settings:** `subscriber.copilot.permission-mode: allow-list`, `subscriber.copilot.allowed-tools` (the rules, comma-separated in the
  environment: `SUBSCRIBER_COPILOT_ALLOWED_TOOLS`) and `subscriber.copilot.denied-tools` (`SUBSCRIBER_COPILOT_DENIED_TOOLS`). Each rule becomes
  `--allow-tool '<rule>'` or `--deny-tool '<rule>'`; no blanket flag is added.
- **Fail safe:** rules configured with another mode stop the start (a restriction the person believes in must never be ignored); the
  allow-list mode with no allow rule stops the start.
- **Rules are untrusted text on a command line**, so a rule may contain only letters, digits, space and `_ . : * ? / \ ( ) ~ -` (it can
  hold a glob such as `write(C:/work/**)`); a quote, `;`, `$`, backtick, `&`, `|`, `<`, `>`, `%`, a comma or a line break stops the start. A
  rule is placed in a single-quoted literal in the generated script, where none of the allowed characters has a meaning.
- **Headless** used to put `--allow-all-tools` in its script whatever the mode (nobody can approve in a headless run); that stays for the other two
  modes, and the allow-list mode replaces it, so a headless session is as restricted as a window session.
- **Not in scope:** choosing the model (the Copilot CLI reads `COPILOT_MODEL` from the environment, which the engine passes on), and a rule language of
  our own (the rules are the CLI's, see `copilot help permissions`).

## Risks / Trade-offs

- **Spring Shell 4.0.3 on Boot 4.1.2 is untested** → task 7.1 checks it first; if it fails, the console task stops and reports instead of
  working around it.
- **The console reads standard input** → it only starts with a terminal; the window launcher does not use the engine's stdin, so the two
  do not interfere.

- **`-i` with `--agent` and `--session-id` is untested together** → the first task is a spike with the real CLI; if a flag
  does not combine, the design adapts before anything else is built.
- **`start /wait` and PowerShell 5.1 quirks** → the script writes its own `exit.json`; the exit code of `start` is never relied on.
- **Messages lost while the engine is down** → documented limit; durable queue (JetStream) is a later change.
- **`allow-all-tools` in an unattended window** → roots allowlist, repository must be a git repository, the report is a snapshot
  treated as data, `permission-mode: ask` is available; the audit CSV can quote hostile code, so the prompt says it is data.
- **Report freshness uses file times on one machine** → fine for the same-machine case; stated as an assumption.
- **A person closes the window early** → `exit.json` still appears (`try/finally`); Ctrl+C was observed to give exit code 0, so the
  missing report is what makes it `ERROR` ("ended without a report"); it is not distinguished from a failure.
- **Second engine on the same data directory** → fails fast with a clear message.
- **The audit subject's default agent has no code-fix route** → `appmod-harness` answers "out of scope" until `security-fixer`
  exists; configurable per subject.

## Migration Plan

1. Build `subscriber-engine/` next to the Node subscriber; do not run both on the same subjects at once.
2. Pass the tests and the manual acceptance run.
3. Remove the Node subscriber, its tests and script; update the NATS docs.
Rollback: restore the three files from git history.

## Open Questions

- Whether to add an admin cancel (needs the process handle after a restart); the states and events leave room.
- Whether the audit step or the fixer should record the audited commit in the report so the engine can warn when the repository
  moved on; owned by the `security-fixer` and audit-publish changes.
