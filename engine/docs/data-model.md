# Data model

subscriber-engine keeps every fix session in one embedded **H2** database stored in a file. Schema changes are Flyway migrations
in `src/main/resources/db/migration/` (`V1__create_fix_session.sql`, `V2__add_worktree_to_fix_session.sql`). A test (`DataModelDocTest`) fails when this
document and the migrations disagree on tables or columns.

## Standard-SQL rule

Migrations use standard SQL only: `VARCHAR`, `INTEGER`, `BIGINT`, `BOOLEAN`, `TIMESTAMP WITH TIME ZONE`, identity columns, primary
and foreign keys, plain indexes. No array or JSON columns, no engine-specific types or functions. Queries follow the same rule
(paging with `OFFSET n ROWS FETCH NEXT m ROWS ONLY`, plain comparisons). The reason is cost of change: moving to PostgreSQL later
should mean a different driver and JDBC URL, not a different schema.

**States are a lookup table, not a `CHECK`.** `fix_session.state` and `fix_session_event.state` reference `fix_session_state(name)`
by foreign key. A `CHECK (state IN (...))` was tried first and does not work with H2 2.4.240: H2 builds a constant set for the check
inside the connection that creates it (Flyway's), and every later connection then fails with "The database has been closed". A
test keeps the lookup table equal to the Java enum `SessionState`.

## Data directory, backup and the single-instance rule

- The database file is `<data dir>/subscriber.mv.db`. The directory comes from `subscriber.data-dir` (environment variable
  `SUBSCRIBER_DATA_DIR`, default `./data`). Session folders (the report snapshot, the prompt, the launcher script, logs and the
  exit record of each session) live under `<data dir>/sessions/<session id>/`; they are written by later parts of the engine.
- **Backup:** stop the engine and copy the whole data directory. Do not copy the file while the engine runs.
- **Single instance:** the database is opened in embedded mode, so only one process can open the file at a time. A second engine on
  the same directory exits with a non-zero code and prints *"The subscriber-engine data directory `<dir>` is already in use by
  another process"* with the action to take (stop the other instance or choose another `SUBSCRIBER_DATA_DIR`).
- **A future web application is a second process**, so it cannot open this file. It reads through the engine's read-only HTTP API
  (see the design), never through the file.

## States

| State | Final | Meaning |
|---|---|---|
| `QUEUED` | no | The message was accepted and waits for a free slot. |
| `IN_PROGRESS` | no | The agent's window is open (or the headless run is going). |
| `DONE` | yes | The agent ended cleanly and its report says `succeeded`. |
| `PARTIAL` | yes | The agent ended cleanly and its report says `partially_succeeded`. |
| `NEEDS_INPUT` | yes | The agent ended cleanly and its report says it was blocked on a decision. |
| `ERROR` | yes | The agent failed, ended without a fresh report, or could not be started; `reason` says which. |
| `INTERRUPTED` | yes | The window was gone after a restart and left no exit record. |
| `REJECTED` | yes | The message failed a check and nothing was launched; `reason` says which. |

A session in a final state is never changed again: the repository's state changes are guarded in SQL.

## Tables

### `fix_session_state`

The eight valid states, referenced by foreign key from the two tables below.

| Column | Meaning |
|---|---|
| `name` | The state name, one of the values in the table above. |

### `fix_session`

One row per accepted or rejected message.

| Column | Meaning |
|---|---|
| `id` | Session id, a UUID. It is also the agent's own session id, so `copilot --resume <id>` reopens the conversation. |
| `seq` | Arrival order, assigned by the database. Messages received in the same instant keep their order; the queue and the lists sort on it. |
| `state` | Current state (a `fix_session_state` name). |
| `subject` | The NATS subject the message arrived on. |
| `source` | The label of the subject's source, for example "Security audit". |
| `report_path` | The payload as received: the report path, or whatever was sent when the message was rejected. Shortened to 1024 characters. |
| `snapshot_path` | The engine's copy of the report that the agent works from, taken when the message was accepted. |
| `repository_path` | The repository the agent works in. Empty when it could not be determined. |
| `agent` | The agent name used for the session. |
| `runner` | `window` or `headless`; set when the session starts. |
| `engine` | The coding tool, currently `copilot`; reserved so another tool can be added. |
| `received_at` | When the message was received (UTC). |
| `started_at` | When the session was launched. |
| `ended_at` | When the session reached a final state. |
| `pid` | Process id of the launcher (the window's wrapper), for the liveness check after a restart. |
| `pid_started_at` | Start time of that process; together with `pid` it identifies the process, because ids are reused. |
| `exit_code` | The agent's exit code from the exit record. A person interrupting with Ctrl+C was observed to give 0, so this alone never decides the state. |
| `head_start` | The repository's commit when the session started. |
| `head_end` | The repository's commit when the session ended, from the exit record. |
| `dirty_at_start` | Whether the working tree had uncommitted changes when the session started. |
| `outcome` | What the agent reported in `.appmod/result.json` (`succeeded`, `partially_succeeded`, `needs_input`, `failed`). |
| `reason` | Why the session is in an error or rejected state, or other detail. Shortened to 2000 characters. |
| `work_name` | The git worktree the session worked in: its name, which is also its folder name, `<repository folder>-fix-<first 8 characters of the id>`. Empty for a session without a worktree (isolation `none`, a rejected session, a session from before V2). |
| `work_path` | The worktree's folder. |
| `work_branch` | The branch the agent commits on, `appmod/fix-<first 8 characters of the id>`. |
| `base_commit` | The commit the worktree started from: the audited commit named by the report when it could be used, `HEAD` otherwise. |
| `fix_commits` | How many commits the agent made on the branch since the base commit, counted when the session reached a final state (also for `ERROR` and `INTERRUPTED`). Empty when there is no worktree or the count could not be read. |
| `source_requested` | The source branch asked for when the report was published (the `source-branch` header). Empty when none was given. The duplicate rule compares it. |
| `source_branch` | The branch the worktree started from. Empty when it started from a commit that is not a branch tip, and for sessions from before V3. |
| `source_choice` | Which choice applied: `publish`, `saved`, `report-commit`, `default-branch` or `head`. |
| `local_changes` | True when the worktree started from a snapshot commit holding the checkout's uncommitted work (`--unsafe-local-changes`). |
| `publish_state` | `none`, `awaiting_confirmation`, `pushed` or `push_failed`. Empty (read as `none`) for sessions from before V3. |
| `pull_request_state` | The pull-request step: `none`, `created`, `failed` or `skipped`. |
| `pull_request_link` | The first link the pull-request command printed. |

### `fix_session_event`

One row per state a session entered, in order.

| Column | Meaning |
|---|---|
| `id` | Event id; identity. |
| `session_id` | The session this event belongs to. |
| `occurred_at` | When the state was entered (UTC). |
| `state` | The state entered (a `fix_session_state` name). |
| `detail` | A short note, for example "window opened" or "reopened by operator". |
