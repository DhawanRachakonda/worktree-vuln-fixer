# Design

## Context

`WorktreeService.create` computes the folder (`locate`), then runs `git worktree add -b`. On Windows git failed inside a long path with
`fatal: could not create directory of '.git/worktrees/<name>': Filename too long`, and the engine reported only the first line of git's output
("Preparing worktree (new branch ...)"). The engine's tests had never run off Windows, and the credentials code (`NatsOptionsFactory`) had only been
tested against a mock.

## Goals / Non-Goals

**Goals:** refuse early and clearly where Windows cannot cope; show git's real error; prove credentials against a real server; make the tests run on Linux.
**Non-Goals:** enabling long paths in git or Windows (`core.longpaths`, the registry): that changes the person's machine; credentials in the form of a
`.creds` file (needs an operator-mode server: a different test setup); macOS (no machine to run it on); a window runner for Linux.

## Decisions

- **Two limits, from the real failure.** The folder limit (200) leaves room for the files inside the worktree. The second limit (230 for
  `<repository>/.git/worktrees/<name>`) comes from git's own folder, which is the longer path because it holds the repository path *and* the name: a first
  try with only the folder limit still failed in git. The limits apply only on Windows, decided by the operating system name and passed in by the tests.
- **Refuse, do not repair.** The engine does not shorten names or enable long paths; the operator picks a shorter location, which the message says.
- **The reason is git's `fatal:`/`error:` line**, falling back to the first line. One small function used for every refusal of git in the service.
- **The live credentials test starts its own server** on a free loopback port (`NATS_SERVER_EXE`), so it never touches a running server, and checks the log
  output for both the wrong and the right secret.
- **Linux tests set a permission mode** instead of the guard being relaxed: the guard is right to refuse an unattended engine without a decision, and
  the tests that start the whole application do not run an agent.

## Risks / Trade-offs

- The limits are conservative guesses from one failing case (a 281-character admin path failed; a 222 one was not tried against git). They can be raised if
  Windows long paths are enabled on the machine; there is no setting for that yet.
- A real Linux machine, not WSL, and macOS were not used; WSL runs a real Linux kernel and a real `sh`, which is what the launcher scripts need.
