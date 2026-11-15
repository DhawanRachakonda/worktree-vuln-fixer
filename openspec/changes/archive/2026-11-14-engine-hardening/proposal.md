# Proposal

## Why

Three things about the subscriber-engine were listed as "not tried": a NATS server that requires credentials, any operating system but Windows, and a repository
folder whose name makes the worktree path too long for Windows. Trying them found real defects: the engine's whole test suite failed on Linux (52 of 368),
a refused worktree reported git's useless first line ("Preparing worktree ...") instead of its error, and a worktree under a long path made git fail
with "Filename too long" halfway through. Each is cheap to close and none should wait for a user to hit it.

## What Changes

- **Long paths on Windows.** Before it runs git, the worktree service refuses, with a message that says what to shorten, a worktree folder over 200
  characters and a repository whose `.git/worktrees/<name>` folder would pass 230 (git keeps the worktree's own files there, and Windows stops at 260). The
  session ends `ERROR` with that reason, nothing is created, the queue goes on.
- **Git's real reason.** When git refuses to create or remove a worktree, the reason shown is git's `fatal:` or `error:` line, not the first line it prints.
- **Credentials.** An opt-in live test (`-Pnats-tests`, `NATS_SERVER_EXE`) starts its own `nats-server` with token and with user and password, and checks that
  the right credentials connect and deliver, that a wrong token and missing credentials never connect, and that no secret reaches the log.
- **Linux.** The engine's tests were run on Linux (WSL, JDK 25). The tests were fixed where they assumed Windows: every context test now sets a
  permission mode (on Linux the `auto` runner is headless, which the headless guard refuses without one, as designed), and one test asserted a message
  of the wrong wording.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `fix-session-worktrees`: a requirement for worktree paths that Windows can handle, and for the reason a refusal carries.

## Impact

- `subscriber-engine`: `WorktreeService` (two checks, the reason of a refusal), tests (`WorktreeServiceTest`, new `NatsAuthLiveTest`, the context tests), docs
  (`spec-coverage.md`, `operations.md`).
- No change to the settings, the API, the console or the agents.
