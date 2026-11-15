# Tasks

## 1. Data model

- [x] 1.1 Add migration `V2__add_worktree_to_fix_session.sql` (`work_name`, `work_path`, `work_branch`, `base_commit`, `fix_commits`, all
      nullable, standard SQL), the matching fields on `FixSession` with a `workingPath()` method (worktree folder, else repository),
      and repository methods that write the four worktree fields when a session is marked started and `fix_commits` when it is marked
      final; update `docs/data-model.md`; verify `MigrationTest` (fresh database, and a database that holds a V1 row, which keeps empty
      worktree fields), `FixSessionRepositoryTest` (round trip of all five fields, empty for a session without a worktree, the count
      written only with the final state) and `DataModelDocTest` pass

## 2. Setting

- [x] 2.1 Add `subscriber.subjects[].isolation` (`worktree` or `none`) with the default taken from `subscriber.isolation`
      (`SUBSCRIBER_ISOLATION`, default `worktree`), validated like the other settings; document it in the README settings table;
      verify `SubscriberPropertiesTest` (default is `worktree`, a subject can override it, an invalid value stops the start naming the
      setting) and `ContextLoadsTest` (the environment variable reaches the setting)

## 3. Worktree service

- [x] 3.1 Add a typed reader of the report's `commit` column (Jackson CSV from the Spring Boot BOM, a record with only that field,
      unknown columns ignored); verify tests for a valid value, a quoted value, a byte order mark, no `commit` column, no data row,
      an empty value, a value that is not 7 to 40 hexadecimal characters, a file that is not CSV, and a very large file (capped)
- [x] 3.2 Add the worktree service: name `<repository folder>-fix-<id8>` with unsafe characters replaced, branch `appmod/fix-<id8>`,
      folder next to the repository checked with the intake's real-path and allowed-roots rule, base commit from 3.1 when
      `git rev-parse --verify` finds it and `HEAD` otherwise (reporting why), `git worktree add` run without a shell and with hooks
      disabled (`core.hooksPath` pointing to an empty folder), timeouts and an output cap, and removal of anything the attempt created
      when it fails; verify tests with real temporary git repositories for: the folder, branch and base commit; the person's checkout
      unchanged in branch, HEAD, index and an uncommitted file; a folder outside the allowed roots; a folder that already exists; an
      audited commit that is missing (falls back, with the reason); a repository folder name with `&`, `%` and `'`; a `post-checkout`
      hook that does not run; and no leftover folder or branch after a failure
- [x] 3.3 Add the commit count (`git rev-list --count <base>..<branch>` in the worktree); verify tests for no commits, two commits, a
      worktree folder that is gone (empty count, no exception) and a repository that git cannot read

## 4. Dispatch, lifecycle and recovery

- [x] 4.1 In the dispatcher, for a subject with isolation `worktree`: create the worktree right before the launch, record it with
      the start (name, folder, branch, base commit, `head_start` of the worktree) and its event, launch with the worktree as the working
      directory, and end the session as `ERROR` with the reason when creation fails, so that the next session still starts; keep
      the same-repository rule keyed by the repository; leave `none` as it is today; verify `SessionDispatcherTest` (the launcher
      receives the worktree folder, the record holds the fields and the event, a creation failure gives `ERROR` and the queue goes on,
      isolation `none` creates nothing, two sessions for one repository still never overlap)
- [x] 4.2 Rewrite `prompt.md` at start with the worktree folder as the repository to work in and the original checkout named as not to
      be changed; verify `PromptWriterTest` (both paths present exactly, also with shell characters, and the rules text unchanged)
- [x] 4.3 Read the agent's report and the end commit from `workingPath()` in `SessionLifecycle` and in `SessionRecovery`; when a
      session reaches a final state record the commit count and its event (also for `ERROR` and `INTERRUPTED`, and an event when the
      count cannot be read); verify `SessionLifecycleTest` and `RecoveryTest` with a worktree (a report in the worktree counts and one
      left in the checkout does not; restart with the window still open, with the window ended and with the window gone; the count
      recorded in each final state)

## 5. Showing it

- [x] 5.1 Return `workName`, `workPath`, `workBranch`, `baseCommit` and `fixCommits` from the read API (null without a worktree);
      verify `SessionApiTest` (a session with a worktree, one without, the list and the single session) and that the API stays
      read-only
- [x] 5.2 Show the worktree in the console: `session <id>` prints the five fields and the review and removal commands built from
      validated values; `sessions` gets a `FIXES` column (the count, or `-`); all text goes through the control-character escaping;
      verify `ConsoleCommandsTest` (the fields, the four commands, the marker for a session with commits and none for one without or
      with zero commits, a branch or folder with control characters is escaped) and that no command changes a record
- [x] 5.3 Open `reopen` in `workingPath()` and refuse, saying why, when the worktree folder is gone; verify `ReopenServiceTest` (the
      window is opened with the worktree folder, the refusal for a removed worktree, the existing refusals unchanged) and an opt-in
      window test that a reopened session opens a real window in a real worktree, with a stand-in agent

## 6. Documentation

- [x] 6.1 Document the worktree in `docs/operations.md` (where the fixes are, how to review, merge and remove them with the commands
      the console prints, that uncommitted work is not included, the cold build, that nothing is deleted by the engine), in
      `docs/connecting.md` (the new API fields) and in the README (the flow and the `isolation` setting); verify by following each
      documented git command once on the worktree of a real session made in 7.1

## 7. Integration

- [x] 7.1 End to end with the real NATS server, the real engine and a stand-in agent that commits twice in its working directory:
      publish an audit report whose CSV names an older commit of a scratch repository while the person's checkout has an uncommitted
      change; check the worktree, branch and base commit on disk, the five fields and the events in the database, in the API and in
      the console, that the checkout is unchanged, a restart in the middle of a session with its worktree, and a creation failure;
      write the expectations in `docs/e2e-worktrees.md` before the run and the results after it
- [x] 7.2 Walk every scenario of `specs/fix-session-worktrees/spec.md` and add it to `docs/spec-coverage.md` (scenario to test or
      recorded run, with gaps stated plainly); verify none is without a proof or an honest gap note, run `./gradlew build` and
      `openspec validate "fix-worktrees" --strict`
