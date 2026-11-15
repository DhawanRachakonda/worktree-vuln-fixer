# End-to-end run for worktrees (task 7.1 of the change `fix-worktrees`)

The real NATS server (the one started by the user on `localhost:4222`), the real engine jar, the real window runner, real git and the
Node publisher of that time (since removed: a report is now published with the console command `publish`). The agent is a stand-in command, `commitcopilot.cmd`: in its working directory it logs where it is, makes a
commit, waits about 12 seconds (so the engine can be stopped in the middle), makes a second commit, writes
`.appmod/result.json` with `{"status":"succeeded"}` and exits 0. It is not Copilot: it proves what the engine does around the agent.

## Setup

- Allowed root `se-wt/root` with three git repositories:
  - `shop`: two commits, **c1** (the audited one) and **c2** (later; it changes `a.txt`). The person's checkout is on its branch at
    c2 with an **uncommitted change** to `a.txt`, a staged new file and an **untracked** file. `shop/.appmod/security-audit/issues.csv`
    names **c1** in its `commit` column.
  - `norepo-commit`: `git init` only, no commit, with an audit CSV, to make worktree creation fail.
- Messages, published in this order on `securityaudit.issuefixesAgent` (isolation left at its default, `worktree`):
  1. `norepo-commit/.appmod/security-audit/issues.csv`
  2. `shop/.appmod/security-audit/issues.csv`
- The engine is killed (forcefully) while session 2's window is running, then started again on the same data directory.
- Afterwards the engine is started in a terminal (a pseudo-terminal) and `sessions` and `session <id>` are run in its console.

## Expectations, written before the run

| # | Expectation |
|---|---|
| F1 | Session 1 (no commit in the repository) ends `ERROR` with a reason that starts "the worktree could not be created"; it has no worktree fields, no folder or branch was left behind, and session 2 still starts. |
| F2 | For session 2 a folder `shop-fix-<id8>` exists next to `shop`, on the branch `appmod/fix-<id8>`; its `HEAD` was **c1** at the start (not c2) and `a.txt` there has the c1 content. |
| F3 | The person's checkout is unchanged by the whole run: same branch, `HEAD` still c2, the same uncommitted change, the same staged file, the same untracked file (compared by `git status --porcelain`, `git diff` and file contents before and after). |
| F4 | The session record has `work_name`, `work_path`, `work_branch`, `base_commit` (the full c1 id) from the start, and `fix_commits` = 2 at the end. The API returns the same five fields. |
| F5 | Events of session 2, in order: `worktree created: <name> on <branch> from <c1 short>`, the window start, and at the end `2 commits on <branch>`. No "report commit not used" event (c1 was usable). |
| F6 | The agent's working directory (from the stand-in's log) is the worktree, and its `.appmod/result.json` is in the worktree, not in `shop`. |
| F7 | Killing the engine while the window runs and starting it again: the engine follows the window again ("still running"), the session ends `DONE` from the worktree's report, and the worktree fields and the count (2) are in the record. |
| F8 | After the session the worktree folder and the branch still exist: the engine deleted nothing. |
| F9 | In the console, `sessions` shows `2` in the `FIXES` column for session 2 and `-` for session 1, and `session <id>` shows the name, folder, branch, base commit, `commits made 2` and the four git commands. |
| F10 | The printed commands work when followed by hand: `git log base..branch` lists exactly 2 commits; `git diff base..branch` shows the two new files; `worktree remove` and then `branch -D` succeed and leave the checkout as it was (task 6.1). |

## Result

Run on 2026-11-11 (times in the API are UTC). Two sessions ran, then a third on `shop` for the mid-session kill:

```
fbdda768 norepo-commit  ERROR  reason: the worktree could not be created: the repository has no commit to start a worktree from
f5a321ff shop           DONE   shop-fix-f5a321ff  appmod/fix-f5a321ff  base 047424f (c1)  2 commits
cfc1e144 shop           DONE   shop-fix-cfc1e144  appmod/fix-cfc1e144  base 047424f (c1)  2 commits   (engine killed 4 s into its window)
```

| # | Result | Evidence |
|---|---|---|
| F1 | Met | Session `fbdda768`: `ERROR`, reason as above, events `QUEUED -> ERROR`, no worktree fields; no folder or branch for it exists; session `f5a321ff` started at once. |
| F2 | Met | `shop-fix-f5a321ff` next to `shop` on `appmod/fix-f5a321ff`; the first commit's parent is c1 (`047424f`), not c2; `a.txt` there held `line one` only (the c1 content). |
| F3 | Met | Before and after each of the three runs and after the cleanup: branch `master`, HEAD `0d2c4eb` (c2), the modified `a.txt`, the staged `staged.txt` and the untracked `untracked.txt`, compared by `git status --porcelain`, `git diff`, `git diff --cached` and file hashes: identical. |
| F4 | Met | API and database: `workName`, `workPath`, `workBranch`, `baseCommit` = the full c1 id, `fixCommits` = 2; `headStart` = c1, `dirtyAtStart` = true (describes the person's checkout). |
| F5 | Met | `QUEUED received`, `QUEUED worktree created: shop-fix-f5a321ff on appmod/fix-f5a321ff from 047424f`, `IN_PROGRESS window started`, `DONE succeeded`, `DONE 2 commits on appmod/fix-f5a321ff`. No "report commit not used" event. |
| F6 | Met | The stand-in's log shows its working directory as the worktree; `.appmod/result.json` is in the worktree, and the checkout's `.appmod/` holds only `security-audit/`. |
| F7 | Met on the second attempt | The first kill came after the window had ended (the tool call took about 8 seconds), so that restart finalized the session from its exit record, as in the earlier run. On the third session the engine was killed 4 seconds into the window; the restarted engine logged "is still running; following it again" and recorded `DONE` from the worktree's report, with the worktree fields and `fixCommits` = 2. |
| F8 | Met | Both worktrees and both branches were still there after their sessions ended, until they were removed by hand. |
| F9 | Met | In a pseudo-terminal console: `sessions` listed `2` under `FIXES` for both `DONE` sessions and `-` for the `ERROR` one; `session f5a321ff-...` printed the name, folder, branch, base commit, `commits made 2` and the git commands. |
| F10 | Met after a fix | `git log base..branch` listed exactly 2 commits and `git diff --stat` the two new files. **Found by this step:** the printed `git worktree remove` refused ("contains modified or untracked files") because the agent always leaves untracked files in the worktree (`.appmod/result.json`). The console now prints `worktree remove --force`, preceded by `git -C <worktree> status --short` to see what `--force` would drop; with those, removal and `branch -D` worked and the checkout was still identical. |

**Things worth knowing from the run**

- The run's CSV named the audited commit as a bare id. The real audit tool writes the cell as `commit=<id>, dirty=<true|false>`, which was
  found afterwards, when the kit's CSV writer was run by hand; the reader now accepts both and the unit tests use both. The run itself
  was not repeated with the labelled form.
- The worktree never shows the audit CSV (it is untracked in the checkout, and the worktree starts from c1); the agent works from the
  engine's snapshot, which the prompt names.
- `head at start` of the session is the worktree's start (the base commit), not the person's checkout HEAD, which was c2.
- The list's `RECEIVED` column now shows whole seconds so a row fits an 80-column terminal better.
