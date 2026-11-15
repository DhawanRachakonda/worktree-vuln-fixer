# Proposal

## Why

subscriber-engine runs a fix session in the repository's own checkout. The agent switches nothing and commits where the person
has the repository open, so a fix session either blocks the person's own work or mixes the agent's commits and file changes into it.
A person also cannot tell afterwards where a session's changes ended up. Fix sessions should work on their own copy of the code, and
the engine should record where that copy is, so the person can review, merge or discard the fixes without having stopped working.

## What Changes

- For each fix session the engine creates a **git worktree** (a second folder on a new branch, sharing the repository's history)
  before it opens the window, and the agent works there. The person's checkout is neither switched nor changed.
- The worktree is named `<repository folder>-fix-<first 8 characters of the session id>`, sits next to the repository inside an
  allowed root, and is on the branch `appmod/fix-<first 8 characters of the session id>`. It starts from the commit the audit looked
  at when the report names one (a CSV with a `commit` column that exists in the repository), and from `HEAD` otherwise.
- The session record in H2 keeps the worktree's **name, folder, branch, base commit and the number of commits the agent made**
  (counted when the session ends). Events record that the worktree was created and what it holds at the end.
- The console shows it (`session <id>` in full, `sessions` with a marker for sessions that have fixes) and the read API returns the
  same fields. `reopen` opens its window in the worktree.
- The engine reads the agent's `.appmod/result.json` from the worktree, including after a restart.
- A worktree that cannot be created ends the session as `ERROR` with the reason; the next session still starts.
- Each subject has an `isolation` setting, `worktree` (the new default) or `none` (the previous behaviour, in the checkout).
- Worktrees are **never deleted by the engine**: they are the person's review material.
- **BREAKING**: sessions now start in a worktree by default, so the agent no longer sees uncommitted edits of the main checkout, and
  a new build in the worktree starts without the checkout's installed dependencies and build output. Set `isolation: none` on a
  subject to keep the old behaviour.

## Capabilities

### New Capabilities
- `fix-session-worktrees`: isolating each fix session in its own worktree and branch, recording it durably, showing it to the person
  (console and read API), and opening a finished session's window in it.

### Modified Capabilities

None. The capability `issue-fix-subscriber` is still a pending delta of the change `subscriber-engine` (nothing is archived yet), so
this change states its requirements as a capability of its own that extends the engine. Archive `subscriber-engine` first.

## Impact

- `subscriber-engine`: a new Flyway migration (five nullable columns on `fix_session`), a worktree service that runs `git`, changes
  to the dispatcher, lifecycle, recovery, `reopen`, the read API, the console and the prompt, a subject setting, and the docs
  (`data-model.md`, `operations.md`, `connecting.md`, README, `spec-coverage.md`).
- No change to the Node publisher, the NATS subjects or the audit and fixer agents. The `security-fixer` change depends on this one
  only for where it runs, not for what it does.
- New dependency: a CSV reader for the audit report's `commit` column (Jackson CSV from the Spring Boot BOM).
- `git` must be on the PATH of the engine (it already is for the commit probe).
