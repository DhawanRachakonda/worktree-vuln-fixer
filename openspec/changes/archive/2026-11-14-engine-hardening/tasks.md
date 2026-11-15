# Tasks

## 1. Worktrees

- [x] 1.1 Refuse on Windows a worktree folder over 200 characters and a `.git/worktrees/<name>` folder over 230, before git runs; verify
      `WorktreeServiceTest` for both limits (nothing left behind) with the operating system passed in, and that git itself fails at the length the
      second limit guards (observed once by hand: git failed at 281 characters)
- [x] 1.2 Show git's `fatal:`/`error:` line as the reason of a refused worktree; verify a test where a file stands where the folder should go

## 2. NATS credentials

- [x] 2.1 Add `NatsAuthLiveTest` (opt-in, its own `nats-server`): token and user and password connect and deliver, a wrong token and no credentials never connect, and
      neither the wrong nor the right secret is logged; run it with `-Pnats-tests` against a real `nats-server` and record it in `docs/spec-coverage.md`

## 3. Linux

- [x] 3.1 Run the engine's tests on Linux (WSL, JDK 25) and fix what assumed Windows (a permission mode in every context test; a wrong message in a test that only
      runs off Windows); verify the Linux run is green and the Windows build still is; record both in `docs/spec-coverage.md` and `docs/operations.md`

## 4. Documentation

- [x] 4.1 Update the gap lists of `docs/spec-coverage.md` (credentials, non-Windows, long paths) and `docs/operations.md` (headless on Linux needs a permission
      decision; the limits); verify `openspec validate engine-hardening --strict` passes
