# End-to-end run (task 9.1)

A real NATS server (`nats-server` 2.15.2, started by the user on `localhost:4222`), the real engine jar, the real window runner and
the Node publisher of that time (since removed: a report is now published with the console command `publish`). The agent is a stand-in command, `slowcopilot.cmd`, that logs its start and end with the working
directory, waits about 15 seconds so the engine can be stopped in the middle, then writes `.appmod/result.json` with
`{"status":"succeeded"}` and exits 0. It is not Copilot: what it proves is the engine's behaviour around the agent.

## Setup

- Allowed root: `se-e2e/root`, two git repositories `alpha` and `beta` in it, each with a committed empty history.
- Three messages, published back to back, in this order (the window runner, `SUBSCRIBER_MAX_PARALLEL` left at 1):
  1. `securityaudit.issuefixesAgent` -> `alpha/.appmod/security-audit/issues.csv`
  2. `securityaudit.issuefixesAgent` -> `beta/.appmod/security-audit/issues.csv`
  3. `coverity.issuefixesAgent` -> `root/coverity.json` (the Coverity subject's repository is fixed to `alpha`)
- Engine killed (forcefully, as a crash would) while session 1 is `IN_PROGRESS`, then started again on the same data directory.

## Expectations, written before the run

| # | Expectation |
|---|---|
| E1 | Three sessions are recorded, in the order published (`seq` ascending), with repositories `alpha`, `beta`, `alpha` and subjects as published. |
| E2 | At most one session is `IN_PROGRESS` at any moment. The stand-in's log never shows two runs overlapping. |
| E3 | Session 1 starts first; sessions 2 and 3 are `QUEUED` while it runs. |
| E4 | When the engine is killed, session 1's window keeps running (the engine does not own it). After the restart the engine follows that window again, so session 1 ends `DONE` with its own exit record, and is **not** `INTERRUPTED`. |
| E5 | Sessions 2 and 3 are still `QUEUED` after the restart and run afterwards in their original order, 2 then 3. |
| E6 | All three end `DONE` with outcome `succeeded` and exit code 0. The stand-in's log has exactly three starts and three ends: nothing ran twice. |
| E7 | Every session has events `QUEUED`, `IN_PROGRESS`, `DONE` in that order, in the database and through the API (`/api/sessions/<id>/events`). |
| E8 | The API list is newest first, with total 3. |
| E9 | A fourth run: with the engine and the window both killed during a session, the restarted engine marks that session `INTERRUPTED` (no exit record) and carries on. |

## Result

Run on 2026-11-11 (times below are UTC, as the API shows them). Final state of the four sessions:

```
64e7b009 securityaudit alpha  DONE         succeeded  exit=0  19:07:06 -> 19:07:22   QUEUED -> IN_PROGRESS -> DONE
467eb03c securityaudit beta   DONE         succeeded  exit=0  19:07:38 -> 19:07:53   QUEUED -> IN_PROGRESS -> DONE
51fee7cc coverity      alpha  DONE         succeeded  exit=0  19:07:55 -> 19:08:11   QUEUED -> IN_PROGRESS -> DONE
43fbaf7c securityaudit beta   INTERRUPTED  -          -       19:08:48 -> 19:09:05   QUEUED -> IN_PROGRESS -> INTERRUPTED
```

| # | Result | Evidence |
|---|---|---|
| E1 | Met | Four seconds after publishing: three sessions, repositories alpha, beta, alpha, subjects as published, in publishing order. |
| E2 | Met | The stand-in's runs (log): 00:37:06-00:37:22, 00:37:38-00:37:53, 00:37:56-00:38:11 local time; none overlaps. |
| E3 | Met | Same snapshot: session 1 `IN_PROGRESS`, sessions 2 and 3 `QUEUED`. |
| E4 | Met, in two different ways (see below) | Neither kill left a session `INTERRUPTED`. |
| E5 | Met | After each restart the queue went on in the original order: beta (session 2) began at 19:07:38 on the first restart, alpha (session 3) at 19:07:55. |
| E6 | Met | Sessions 1 to 3: `DONE`, outcome `succeeded`, exit 0. The log has one start and one end per session; nothing ran twice. |
| E7 | Met | Events as in the table above, in the database order, read through `/api/sessions/<id>/events`. |
| E8 | Met | `GET /api/sessions` returned 51fee7cc, 467eb03c, 64e7b009, total 3 (newest first). |
| E9 | Met | After the engine, the window's PowerShell and its `cmd.exe` wrapper were stopped (three process IDs, found first by a read-only listing), the restarted engine logged "the window was gone after a restart and left no exit record" and set `INTERRUPTED`. |

**What differs from the plan (E4).** The plan was one kill while session 1's window was open. The first kill landed in the same second
as the end of session 1's window (the tool call that sent it took about 16 seconds), so that restart exercised another case: the window
had ended and left `exit.json`, and the engine logged "ended while the engine was down; finalizing it from its exit record" and set
`DONE`. The case in the plan, the engine killed while the window is open, was done on session 2: the engine was killed 5 seconds
before its window ended, the window kept running (it is not the engine's child in the sense that matters: killing the Java process does
not close it), and the restarted engine logged "is still running; following it again", then recorded `DONE` when the window ended.
Both cases are covered by `RecoveryTest` as well; this run shows them with real processes.

**Things worth knowing from the run**

- A recovered session's last event carries the time the engine learned of the end, not the time the window ended (`DONE@19:07:37`
  for a window that ended at 19:07:22). The session record itself keeps the real end time from the exit record (`endedAt`).
- The queue resumed by itself at once after each restart; nothing had to be republished.
- The stand-in of the fourth session was a grandchild of the killed PowerShell and finished on its own at 19:09:04; the engine had
  already settled that session as `INTERRUPTED`, as it should: only an exit record from the launcher script counts.

## Acceptance with the real Copilot CLI (task 9.2): first, unattended part

Run on 2026-11-11 with nobody at the keyboard, permission mode `ask` (so the agent could not change anything unattended), the kit's
`appmod-harness` agent files in a scratch repository, and the real Copilot CLI (1.0.108, which updated itself to 1.0.109 during the run).

What was seen:

- Publishing the audit CSV recorded a session and the engine opened a window; Copilot started in the scratch repository
  (`copilot.exe` under `node.exe` under the launcher's PowerShell under `cmd.exe`), session `IN_PROGRESS`.
- **The session id is Copilot's own:** a folder named with the engine's session id exists in Copilot's session store, so
  `copilot --resume <id>` has the conversation to resume.
- Copilot stayed running after 45 seconds, waiting (nobody approved or typed anything).
- Ending Copilot by stopping its two processes: the launcher script wrote its exit record, the engine recorded `ERROR`
  ("the agent exited with code -1") with events `QUEUED -> IN_PROGRESS -> ERROR`, and the window was gone. The scratch repository
  had no changes.

**Not seen, because it needs a person: that typing in the window reaches the agent, that the agent asks and receives answers, a
`DONE` or `PARTIAL` outcome from a real agent run, and `reopen` / `copilot --resume` bringing the conversation back in a window.**
Task 9.2 stayed open until a person had done those; the next section is that run.

## Acceptance with the real Copilot CLI (task 9.2): the run with a person

Run on 2026-11-12 by the person at the keyboard: window runner, permission mode `ask`, port 18094, the kit's `security-fixer` agent, the
scratch repository `shop` (audited commit `950dc9d`), the real Copilot CLI.

What was seen:

- Publishing the audit CSV recorded session `fe633526-9acd-4207-bcd5-554c24203e6c`; the engine made the worktree `shop-fix-fe633526` on
  `appmod/fix-fe633526` and opened a window titled with the session; Copilot started in the worktree.
- **Typing reaches the agent:** the person typed `/allow-all` in the window and the agent went on working.
- The agent fixed the issues and wrote its reports; the person ended Copilot with `/exit` (exit code 0). The window closed, the session
  became `DONE` ("succeeded", "4 commits on appmod/fix-fe633526"), events `QUEUED -> IN_PROGRESS -> DONE`, exit record and branch recorded.
- **`sessions` and `reopen <id>`** in the operator console: a window opened, resumed with `copilot --resume`, and the agent answered
  "What did you fix?" from the earlier conversation (the four fixes and the XSS finding it left alone). The session stayed `DONE` and got one
  event, `reopened`.

Differences from the design:

- The agent asked no question in this run, so "the agent asks and receives answers" was not exercised (the person's only input was
  `/allow-all`). The question path of `security-fixer` stays untried with a person.
- With mode `ask` the person had to approve tools one by one or type `/allow-all`; that is the reason for the allow-list mode.
- The run found three defects of the agent, not of the engine (a byte order mark in nine edited files and in `result.json`, the SQL fix
  inside the password commit and missing from `issuesFixed`, a regression test that needs an environment variable); they are task 6.5
  of `security-fixer`.
