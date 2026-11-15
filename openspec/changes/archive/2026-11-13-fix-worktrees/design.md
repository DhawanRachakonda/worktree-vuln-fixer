# Design

## Context

subscriber-engine (change `subscriber-engine`, implemented, not yet archived) starts each fix session by opening a window in the
repository's own checkout. The code paths this change touches, all in `subscriber-engine/src/main/java/dev/subscriber/`:

- `lifecycle/SessionDispatcher` probes the repository (`RepositoryProbe`: commit and dirty flag), records the start, calls
  `SessionLauncher.launch(sessionId, repository, agent)`, and keeps the rule "never two sessions in one repository" in a map keyed by
  the repository path.
- `lifecycle/SessionLifecycle.finish` reads the agent's `.appmod/result.json` with `AgentReport.read(repositoryPath, since)`;
  `SessionRecovery` adopts or finalizes sessions after a restart using the same path.
- The launcher scripts `cd` into the repository, and their `finally` block records `exit.json` with `git rev-parse HEAD` of it.
- `intake/IntakeService` writes `prompt.md` when the message is accepted, with the repository path.
- `console/ReopenService` opens a window in the repository for `copilot --resume`.
- `store/FixSessionRepository` and `fix_session` (migration `V1__create_fix_session.sql`); `docs/data-model.md` is checked against
  the migrations by `DataModelDocTest`.

The audit report is a CSV (`security-audit-csv` skill) whose rows carry a `commit` column: the commit that was audited.

## Goals / Non-Goals

**Goals:**
- A fix session works on its own branch in its own folder; the person's checkout is never switched or changed.
- The engine records which worktree, folder, branch and base commit a session used and how many commits it made, in H2, and shows
  it in the console and the read API.
- Everything that decided things from "the repository" (report, end commit, recovery, reopen) uses the right folder.
- A failure to create the worktree is an ordinary `ERROR` that does not stop the queue.

**Non-Goals:**
- Removing worktrees or branches (no auto-cleanup, no `discard` command; the console prints the git commands). A later change.
- Merging, pull requests or pushing. The person reviews and merges.
- Running two sessions for one repository at once. The rule stays, keyed by the repository.
- Submodules and Git LFS in the worktree.
- Preparing the worktree for building (installing dependencies): that is the agent's job in the session.

## Decisions

### D1. The engine owns the worktree, not the agent
The window must open in the worktree, and the engine reads the result and the end commit from there, so the engine creates it, in
the dispatcher right before the launch: create the worktree, record it, then launch with the worktree as the working directory.
*Alternative:* the agent creates its own branch or worktree by instruction. Rejected: the engine would not know where to look, the
rule could be forgotten by an agent, and a failure would surface only inside a window.

### D2. Name, branch and place
Name `<repository folder name>-fix-<id8>` (letters, digits, `.`, `_`, `-`; anything else replaced, as `SessionFolders.safeName`
does), branch `appmod/fix-<id8>`, folder = a sibling of the repository folder. The folder is checked with the intake's real-path and
allowed-roots rule; if it is outside every allowed root, or already exists, the session ends `ERROR` with that reason. The branch
does not carry a "security" word because the worktree serves every subject.
*Alternatives:* inside the data directory (outside the allowed roots, so it would break the rule that agents only run inside them);
inside the repository under `.appmod/` (nested copy that builds, IDEs and `git status` would see).

### D3. Base commit
Base = the `commit` column of the report when the report is a `.csv` with that column, the first data row's cell is 7 to 40
hexadecimal characters or the form the audit tool writes (`commit=<id>, dirty=<true|false>`, found when the real tool's CSV was read; the tool renders
the reviewed source reference as given), and `git rev-parse --verify <value>^{commit}` finds it in the repository; otherwise `HEAD`. When the report
names a commit that cannot be used, an event says so ("report commit not used: <reason>"). Starting at the audited commit makes the
audit's line numbers exact and moves the question "has the code moved on" to the merge, where git answers it.
The CSV is read with Jackson CSV (typed, `tools.jackson.dataformat:jackson-dataformat-csv`, version from the Spring Boot BOM) into a
record with only the `commit` field and unknown columns ignored, never by splitting text. The snapshot taken at acceptance is what is
read, not the original.
When the cell says `dirty=true` the audit saw uncommitted changes: the commit is still used, and an event says that line numbers may
differ. Uncommitted work of the checkout is not in the worktree. `dirty_at_start` keeps describing the person's checkout.

### D4. Git is run without a shell, with validated arguments
`ProcessBuilder` with an argument list, working directory the repository. Everything that reaches an argument is made by the engine
(folder, branch, a hexadecimal commit); the repository path is only the working directory. Commands: `git worktree add -b <branch>
<folder> <commit>`, `git rev-parse --verify`, `git rev-list --count <base>..<branch>`. Hooks of the repository do not run on creation:
git is called with `-c core.hooksPath=<an empty folder under the data directory>`, so a `post-checkout` hook cannot execute code
because of a message. Output is read with a timeout (30 s) and a size cap, and shown only through the existing printable escaping.

### D5. Data model: five nullable columns on `fix_session`
Migration `V2__add_worktree_to_fix_session.sql` (standard SQL only, as `data-model.md` requires): `work_name`, `work_path`,
`work_branch`, `base_commit`, `fix_commits` (INTEGER). All nullable: a session without a worktree (isolation `none`, rejected
sessions, sessions from before the migration) has them empty. `work_name`, `work_path`, `work_branch` and `base_commit` are written
in the same statement that marks the session started; `fix_commits` is written by `markFinal`. `SessionView`, `FixSession`,
`data-model.md` and `DataModelDocTest` follow.
`head_start` becomes the worktree's HEAD at start, which equals the base commit.
Events: `worktree created: <name> on <branch> from <short base>` at start; `N commit(s) on <branch>` at the end (also for `ERROR`,
`INTERRUPTED`); `report commit not used: <reason>` when D3 falls back.

### D6. One place says where a session works
`FixSession` gets `workingPath()`: the worktree folder when there is one, the repository otherwise. `SessionLifecycle` (report and
`since`), `SessionRecovery` (adopt, finalize), the launcher call and `ReopenService` use it. The repository path stays the key of the
"never two in one repository" rule (`hasActiveForRepository`, the dispatcher's map) and of the recorded `repository`.
The prompt is rewritten at start (`PromptWriter`) with the worktree path as "the repository" and the original path as "where the
person's checkout is, do not change it"; the text of the rules is unchanged.

### D7. Counting commits
At the final state, `git -C <work_path> rev-list --count <base>..<branch>`. When the folder is gone (the person removed it during the
run) or git fails, the count stays empty and an event says so; the session's state is not affected.

### D8. Setting
`subscriber.subjects[].isolation`: `worktree` or `none`, default from `subscriber.isolation` (`SUBSCRIBER_ISOLATION`, default
`worktree`). `none` is today's behaviour exactly. An invalid value stops the start naming the setting, like the other settings.

### D9. Showing it
- Console `session <id>`: the five fields, then the commands to review and remove it, built by the engine from validated values and
  printed with the control-character escaping: `git -C "<repository>" log <base>..<branch>`, `git -C "<repository>" diff
  <base>..<branch>`, `git -C "<folder>" status --short`, `git -C "<repository>" worktree remove --force "<folder>"`, `git -C "<repository>" branch -D <branch>`.
  `--force` is needed because the agent leaves untracked files in the worktree (found by following the printed commands in the
  end-to-end run), so the check for leftovers comes before it.
- Console `sessions`: a `FIXES` column with the commit count, or `-`.
- Read API: `workName`, `workPath`, `workBranch`, `baseCommit`, `fixCommits` on a session (null when none). Read-only as before.
- No message is printed into the console when a session ends: Spring Shell 4 reads its prompt through the JDK console, so an
  asynchronous line would tear the prompt (the reason logs go to a file while the console is on). The marker in `sessions` is read on
  demand.

### D10. Reopen
`ReopenService` opens the window in `workingPath()`; when there is a worktree and its folder is gone it refuses ("the worktree
<name> no longer exists"). The "another session for the same repository is queued or running" check is unchanged.

## Risks / Trade-offs

- **Cold worktree.** No installed dependencies or build output: the first build and test in a session is slow and may need network.
  Documented; the `security-fixer` agent's rules cover installing from a lockfile.
- **Uncommitted work is not seen.** A fix is made against committed code. A person who wants their uncommitted change included must
  commit it first. Documented; `dirty_at_start` tells that it was dirty.
- **Merge conflicts later** when the base is an older audited commit and the code moved on. That is git's normal case and is clearer
  than silently fixing the wrong lines; the event with the base commit makes the distance visible.
- **Worktrees pile up** (disk, branches). Intentional (review material); documented commands remove them. A `discard` command can be
  a follow-up.
- **Windows locks and long paths.** A name from a long repository folder name can pass the path limit; creation then fails and the
  session ends `ERROR` with git's reason. Not worked around.
- **Shallow or partial clones** may lack the audited commit: handled by the fallback to `HEAD` with an event.
- **`git worktree add` changes the repository's metadata** (`.git/worktrees`, a branch). That is the one thing done to the person's
  repository; it does not touch the checkout's files, branch or index.
- **Existing sessions and the migration:** old rows have empty worktree fields and keep working; the migration only adds columns.
