# subscriber-engine

Receives issue-report messages from NATS and runs one coding-agent session per message (GitHub Copilot CLI or Claude Code, chosen by one
setting) in its own git worktree, then records every session and its state in an H2 file database. A report is published from the engine's own
console (`publish`), or from anywhere with the NATS client. It needs **a JDK 25 and nothing else** (Node is not part of it).

**Guides:** [docs/setup.md](docs/setup.md) (new machine to first fix, and how to watch it), [docs/integrations.md](docs/integrations.md) (Coverity, Black Duck Hub,
other scanners, GitHub Copilot app modernization and OpenRewrite, with what was tested), [docs/operations.md](docs/operations.md) (states, restart, session
files, reopen, the console), [docs/connecting.md](docs/connecting.md) (publishing and the read API), [docs/data-model.md](docs/data-model.md),
[docs/spec-coverage.md](docs/spec-coverage.md) (every requirement and the test or real run that proves it).

## Prerequisites

- **JDK 25** (the build declares a Java 25 toolchain; the JDK that launches Gradle does not matter). If
  `./gradlew javaToolchains` does not list it, register it in your **user-level** `~/.gradle/gradle.properties`, never in the project:

  ```properties
  org.gradle.java.installations.paths=C:/Program Files/Microsoft/jdk-25.0.5.21-hotspot
  ```

- A **NATS server** the engine can reach (`NATS_URL`).
- **GitHub Copilot CLI** or **Claude Code** on the PATH and signed in (`SUBSCRIBER_AGENT_TOOL`), and **Windows PowerShell** (for the window runner).
- No database server: data is an embedded H2 file database.

## Build and run

```
./gradlew build          # compile and run all tests
SUBSCRIBER_ALLOWED_ROOTS=C:/src ./gradlew bootRun
curl http://127.0.0.1:8080/actuator/health
```

On Windows use `gradlew.bat`, and set the variable first (`set SUBSCRIBER_ALLOWED_ROOTS=C:/src` in `cmd`, `$env:SUBSCRIBER_ALLOWED_ROOTS =
"C:/src"` in PowerShell). The engine refuses to start without at least one allowed root.

## How a fix happens

Audit, publish, engine, window, record:

1. The SecurityAudit agent writes `<repo>/.appmod/security-audit/issues.csv`.
2. In the engine's console: `publish "<that csv>"` (or `nats pub securityaudit.issuefixesAgent "<that csv>"` from another machine).
3. The engine checks the path, copies the report, records a `QUEUED` session, creates a **git worktree** for it (its own folder and
   branch, so your checkout is not disturbed; see [docs/operations.md](docs/operations.md)) and starts the coding agent in it: in **one window per session**
   (`copilot --session-id <id> -i` or `claude --session-id <id>`, so a person can type) or **headless** (no window, an allow-list of permissions).
   One session runs at a time and never two in one repository.
4. When the agent ends the window closes and the engine records the final state (`DONE`, `PARTIAL`, `NEEDS_INPUT`, `ERROR`,
   `INTERRUPTED`) with the exit code and outcome.
5. Read it with the console, `GET /api/sessions`, or reopen the conversation with `reopen <id>`. The session records the worktree, its
   branch and how many commits the agent made, so you know where the fixes are.

## Operator console

Started from a terminal with `java -jar build/libs/subscriber-engine-0.1.0-SNAPSHOT.jar`, the engine shows a `$>` prompt with the
commands `sessions`, `session`, `events`, `config`, `doctor`, `publish`, `reopen`, `cleanup` and `exit` (which also stops the engine). It starts only in a
terminal (`SUBSCRIBER_CONSOLE=auto`), so `bootRun`, services and scripts run without it. While it is on, logs go to
`<data dir>/engine.log`. See [docs/operations.md](docs/operations.md).

## Data and the one-process rule

The H2 file is `<data dir>/subscriber.mv.db` (default `./data`). Only **one process can open the file at a time**; a second engine
on the same data directory stops at start with a message that names the directory. Back up by copying the folder while the engine
is stopped.

## Configuration

Every setting has an environment variable. An invalid value stops the start with a message naming the setting.

| Setting | Environment variable | Default | Meaning |
|---|---|---|---|
| `subscriber.data-dir` | `SUBSCRIBER_DATA_DIR` | `./data` | Folder of the database and the session folders; letters, digits, space and `. _ : \ / -` only |
| `subscriber.allowed-roots` | `SUBSCRIBER_ALLOWED_ROOTS` | none, **required** | Comma-separated existing folders; reports and repositories must lie inside |
| `subscriber.runner` | `SUBSCRIBER_RUNNER` | `auto` | `window`, `headless`, or `auto` (window on Windows) |
| `subscriber.console.enabled` | `SUBSCRIBER_CONSOLE` | `auto` | `auto` (only in a terminal), `true` (refuses to start without one), `false` |
| `subscriber.isolation` | `SUBSCRIBER_ISOLATION` | `worktree` | `worktree`: each fix session works in its own git worktree on its own branch; `none`: in the repository checkout. A subject can set its own `isolation` in `application.yml` |
| `subscriber.max-parallel` | `SUBSCRIBER_MAX_PARALLEL` | `1` | Sessions at once, 1 to 16; never two in one repository |
| `subscriber.agent-tool` | `SUBSCRIBER_AGENT_TOOL` | `copilot` | The coding agent that runs a session: `copilot` (GitHub Copilot CLI) or `claude` (Claude Code). A session is recorded with the tool that ran it (`engine`) and reopened with that tool |
| `subscriber.copilot.command` | `SUBSCRIBER_COPILOT_COMMAND` | `copilot` | The Copilot CLI command name |
| `subscriber.claude.command` | `SUBSCRIBER_CLAUDE_COMMAND` | `claude` | The Claude Code command name |
| `subscriber.copilot.permission-mode` | `SUBSCRIBER_PERMISSION_MODE` (or `SUBSCRIBER_COPILOT_PERMISSION_MODE`) | `allow-all-tools` | `allow-all-tools`, `ask` (the person approves in the window), or `allow-list` (only the rules below; see `docs/operations.md`). With the headless runner it must be set: `allow-list`, or `allow-all-tools` on purpose; `ask` is refused there |
| `subscriber.copilot.allowed-tools` | `SUBSCRIBER_ALLOWED_TOOLS` (or `SUBSCRIBER_COPILOT_ALLOWED_TOOLS`) | none | Comma-separated tool rules in the syntax of the tool that runs: `shell(git:*)` for Copilot, `Bash(git *)` for Claude Code; required with `allow-list`, refused with any other mode |
| `subscriber.copilot.denied-tools` | `SUBSCRIBER_DENIED_TOOLS` (or `SUBSCRIBER_COPILOT_DENIED_TOOLS`) | none | Rules the agent may never use (they win over every allow rule); only with `allow-list` |
| `subscriber.nats.url` | `NATS_URL` | `nats://localhost:4222` | Must start with `nats://` or `tls://` |
| `subscriber.nats.token` | `NATS_TOKEN` | none | Use only one of token, user and password, or credentials file |
| `subscriber.nats.user` / `password` | `NATS_USER` / `NATS_PASSWORD` | none | Set together |
| `subscriber.nats.credentials-file` | `NATS_CREDENTIALS_FILE` | none | Must exist |
| `subscriber.access.token` | `SUBSCRIBER_ACCESS_TOKEN` | none | At least 16 characters; required for any non-loopback bind |
| `server.address` | `SUBSCRIBER_BIND_ADDRESS` | `127.0.0.1` | Bind address of the read API |

### Subjects

`subscriber.subjects` lists the issue subjects in `application.yml`. Each has a `source` label, an `agent` (default
`appmod-harness`) and either a fixed `repository` or a `report-location` from which the repository is derived:

| Subject | Agent variable | Repository |
|---|---|---|
| `securityaudit.issuefixesAgent` | `SUBSCRIBER_AUDIT_AGENT` | the third parent of `<repo>/.appmod/security-audit/issues.csv` |
| `coverity.issuefixesAgent` | `SUBSCRIBER_COVERITY_AGENT` | `SUBSCRIBER_COVERITY_REPOSITORY` |
| `bdh.issuefixesAgent` | `SUBSCRIBER_BDH_AGENT` | `SUBSCRIBER_BDH_REPOSITORY` |

Which scanner uses which subject, and what was tested for each, is in [docs/integrations.md](docs/integrations.md).

The audit subject's default agent is `security-fixer` (the kit agent that fixes the findings of an audit CSV; it ships in the AppMod kit and must be installed in
the repository, see the kit's README). Set `SUBSCRIBER_AUDIT_AGENT=appmod-harness` to go back to the front-door agent, which has no route for
code-level findings. The Coverity and Black Duck subjects keep `appmod-harness`.
