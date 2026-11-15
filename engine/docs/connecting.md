# Connecting to the engine

How to send the engine a report, how to read what it did, and how a web application should use the read API.

## 1. Publish a report

The engine listens on three NATS subjects. The message body is **one absolute path** of an existing report file, with nothing
else: no quotes, no JSON, no line break. The file and its repository must lie inside one of the allowed roots
(`SUBSCRIBER_ALLOWED_ROOTS`). The engine copies the report when it accepts the message, so the agent works from a snapshot.

| Subject | Who publishes | Agent that gets the session |
|---|---|---|
| `securityaudit.issuefixesAgent` | the SecurityAudit agent's follow-up, or you | `SUBSCRIBER_AUDIT_AGENT` |
| `coverity.issuefixesAgent` | the Coverity pipeline | `SUBSCRIBER_COVERITY_AGENT` |
| `bdh.issuefixesAgent` | the Black Duck Hub pipeline | `SUBSCRIBER_BDH_AGENT` |

From the engine's own console (nothing else to install; it uses the engine's NATS URL and credentials). A relative path is made
absolute, and a missing file, a directory or a subject the engine does not listen to is refused before anything is published:

```
$> publish C:/src/shop/.appmod/security-audit/issues.csv
$> publish C:/reports/coverity.json --subject coverity.issuefixesAgent
```

From another machine, or a pipeline, with the NATS CLI. The command is the standard one below; the path must already be absolute
because the CLI does no check, and it must be a path the **engine's** machine can read:

```
nats pub securityaudit.issuefixesAgent "C:/src/shop/.appmod/security-audit/issues.csv"
```

For the security-audit subject the repository is derived from the report path
(`<repo>/.appmod/security-audit/issues.csv`, three folders up). For the Coverity and Black Duck subjects the repository is the fixed
`SUBSCRIBER_COVERITY_REPOSITORY` or `SUBSCRIBER_BDH_REPOSITORY`.

A message that fails a check (path not absolute, outside the roots, not a file, no `.git` in the repository, the same report
already queued or running) is **not lost silently**: the engine records a session in the state `REJECTED` with the reason, which
you can read through the API below.

Core NATS keeps no messages. A message published while the engine is down or disconnected is gone; publish again.

### Choosing the agent

Each subject's agent comes from its environment variable. The audit subject's default is `security-fixer` (the kit agent that fixes the
findings of the audit CSV, in the session's worktree); the Coverity and Black Duck subjects default to `appmod-harness`.
To send the audit findings to another agent, start the engine with `SUBSCRIBER_AUDIT_AGENT=<agent name>`. The name is passed to
`copilot --agent` or `claude --agent` (by `SUBSCRIBER_AGENT_TOOL`), so it must be an agent that tool can find in the repository (`.github/agents` for Copilot, `.claude/agents` for Claude Code; the kit's installer places both).

## 2. Read the sessions

The read API is on `http://127.0.0.1:8080` by default. It only answers `GET`; every other method is refused with `405`.

```
curl http://127.0.0.1:8080/api/sessions
curl "http://127.0.0.1:8080/api/sessions?state=ERROR&size=10&page=0"
curl http://127.0.0.1:8080/api/sessions/<session id>
curl http://127.0.0.1:8080/api/sessions/<session id>/events
```

| Parameter | Values | Default |
|---|---|---|
| `state` | `QUEUED`, `IN_PROGRESS`, `DONE`, `PARTIAL`, `NEEDS_INPUT`, `ERROR`, `INTERRUPTED`, `REJECTED` (case does not matter) | all |
| `size` | 1 to 100 | 20 |
| `page` | 0 to 100000 | 0 |

The list is newest first and looks like `{"items":[...],"page":0,"size":20,"total":3}`. A bad value answers `400` with
`{"error":"..."}` saying what is allowed; an unknown or malformed session id answers `404`.

A session shows its state, subject, source, report and repository, agent, runner, start and end times, exit code, the git heads at
start and end, whether the repository had uncommitted changes at start, the outcome and the reason. It never shows process ids or
the path of the snapshot. When the session worked in a git worktree it also shows `workName`, `workPath`, `workBranch`, `baseCommit` and `fixCommits`
(the commits the agent made on the branch, counted when the session ended); they are `null` for a session without a worktree. A web
application can show "fixes ready on branch X" from them. The fields and states are described in [data-model.md](data-model.md).

### From another machine

By default the engine binds to `127.0.0.1`, so only this machine can reach it. To bind elsewhere, set `SUBSCRIBER_BIND_ADDRESS`
**and** `SUBSCRIBER_ACCESS_TOKEN` (at least 16 characters); the engine refuses to start with a remote bind and no token. Every
request to `/api` and `/actuator` must then carry the token:

```
curl -H "Authorization: Bearer <token>" http://<host>:8080/api/sessions
```

A missing or wrong token answers `401` before anything is read. Only `/actuator/health/liveness` and `/actuator/health/readiness`
stay open, and they show only `UP` or `DOWN`. Use TLS in front of the engine (a reverse proxy) when the token crosses a network.

## 3. For a web application

- Poll `GET /api/sessions` (the page can simply be refreshed) and show `state`; the `events` of a session give its timeline. A
  server-sent-events feed can be added later on the same records without changing them.
- Treat every text field (`reason`, `outcome`, paths) as untrusted: show it as text, never as markup. It can contain what a report or
  a repository contained.
- Do not write to the engine's H2 file from another process: the file allows one process, and the engine owns it. Everything a web
  application needs is in the API.
- `IN_PROGRESS` after an engine restart is settled by the engine's recovery before it accepts new work; the web application does not
  need to do anything.
