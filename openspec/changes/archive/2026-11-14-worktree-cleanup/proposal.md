# Proposal

## Why

Every fix session leaves a worktree next to the repository, and the engine never deletes it, so they pile up: one folder (often with
installed dependencies) for each session. Today the person removes one by copying a `git worktree remove --force` line from the
`session` output and running it by hand. That is easy to get wrong, and `--force` throws away anything uncommitted without a check. The
engine already knows which worktree belongs to which session, so a person should be able to ask it to remove a finished session's
worktree safely, without the engine ever removing one on its own.

## What Changes

- A new console command `cleanup <session id>` removes the worktree folder of a **finished** session after the person confirms
  with `--yes`. Without `--yes` it removes nothing and says what it would remove and what is kept.
- The branch `appmod/fix-<id8>` and every commit on it are **kept**: only the folder goes. The person removes the branch themselves.
- It refuses when: the session is unknown, not finished, or has no worktree; the folder is already gone; another session is queued or
  running in the repository; the folder is not a worktree that git lists for the session's repository (it never touches a primary
  checkout); the folder or branch name is not the form the engine made; or the worktree holds work that is not committed other than the
  agent's own reports under `.appmod/`.
- The timeline gets one event, `worktree removed`; the session's state and its other records stay. `session <id>` says that the
  folder is gone, and `reopen` already refuses a session whose worktree is gone.
- The `session <id>` output points to `cleanup <id>` instead of the hand-typed `--force` removal.
- **MODIFIED**: the rule "the engine never deletes a worktree" becomes "never on its own": only this command, asked by an operator,
  removes a worktree folder, and no code path removes a branch.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `fix-session-worktrees`: the requirement "A worktree for each fix session" (the engine deletes nothing on its own, but the operator
  may ask for cleanup), and a new requirement for the cleanup itself.

## Impact

- `subscriber-engine`: a `remove` operation on the worktree service (git, argument lists, same safety as `create`), a cleanup service
  and console command, the `session` report text, tests (a real git repository for the service), and docs (`operations.md`,
  `README.md`, `spec-coverage.md`).
- No new setting, no migration, no change to the read API, the NATS subjects or the agents.
