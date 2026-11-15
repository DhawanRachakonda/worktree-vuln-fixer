# Tasks

## 1. Removing a worktree

- [x] 1.1 Add `remove(repository, workPath, branch)` to `WorktreeProvisioner` and `WorktreeService`: proves the folder is a linked worktree of
      the repository (listed by `git worktree list --porcelain`, real-path comparison), the name and branch are the engine's form with the same
      `<id8>`, the folder is inside an allowed root; refuses on uncommitted work outside `.appmod/`; then `git worktree remove --force`
      and `git worktree prune`; the branch is never touched; verify `WorktreeServiceTest` with a real git repository (removed with only
      `.appmod/` leftovers, refused with a modified file, a new file and a staged file, refused for the repository's own checkout, a
      folder git does not list, a wrong name or branch, a folder outside the roots, branch and commits still there afterwards) and
      update `FakeWorktrees`
- [x] 1.2 Add `CleanupService` and the console command `cleanup <id> [--yes]`: the refusals and the dry run of the spec, the
      `worktree removed` event, no change of state; verify `CleanupServiceTest` (every scenario of the spec) and a
      `ConsoleCommandsTest` case that runs `cleanup` through Spring Shell's parser with and without `--yes`

## 2. Showing it

- [x] 2.1 `session <id>` says when the worktree folder is gone and points to `cleanup <id>` instead of the hand-typed `--force` removal;
      verify `ConsoleCommandsTest` for both texts, and that `reopen` after a cleanup refuses

## 3. Documentation

- [x] 3.1 Update `docs/operations.md` (the cleanup command, what is kept, when it refuses), the README console list, and
      `docs/spec-coverage.md` (every scenario of the delta and its proof); verify `openspec validate worktree-cleanup --strict` passes
      and `./gradlew build` is green
