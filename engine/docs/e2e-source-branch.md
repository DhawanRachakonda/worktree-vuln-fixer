# End-to-end run: source branch, push and pull request

Test: `SourceBranchEndToEndTest` (`engine/src/test/java/dev/subscriber/nats/`). Run it with `./gradlew test -Pnats-tests --tests '*SourceBranchEndToEndTest'`
(or as part of `java scripts/Verify.java --live`); it needs a running Docker daemon and nothing else. CI runs it in the `live` job.

## What is real and what is a stand-in

| Real | Stand-in |
|---|---|
| The console's `publish` (`ReportPublisher`) with its checks and the branch preview | The coding agent: after 1.5 seconds it commits one file in its working directory and writes `.appmod/result.json` (`succeeded`) and the exit record |
| A NATS server (`nats:2.10-alpine` in a Docker container on a free loopback port) | The pull-request command: a small Java program that records its arguments and prints a link (no `gh`, `glab` or host was used) |
| The engine's own subscriber, intake, dispatcher, lifecycle, H2 database and Flyway migrations | The remote: a bare git repository on disk, not a hosted service |
| Real git: a repository on a feature branch with uncommitted work, a bare `origin` whose default branch is `develop`, a branch `release/1.0`, real worktrees, a real snapshot commit, a real push | |

## Setup

`shop` (a Spring Boot, a Node.js or a Python project, one test run each; only its files differ) has `main`, `develop` (one commit ahead; the remote's default, so the detected default is `develop`) and `release/1.0` (at the first commit). The person's
checkout is on `feature/wip` with an untracked file `x.txt`. The engine is configured for the subject `securityaudit.issuefixesAgent` with worktree isolation,
publish mode `pull-request` and the stub as the command.

## Expectations, written before the first run

| # | Expectation | Where it is asserted |
|---|---|---|
| E1 | `publish <report> --source-branch release/1.0` says `branch release/1.0` and `given` before it publishes. | `aReportPublishedWithASourceBranch...`: `result.message()` |
| E2 | The header crosses the real NATS server, the engine records a `QUEUED` session with that source branch, (that the body stays only the path is asserted in `ReportPublisherSourceBranchTest`). | same test: a session exists and ends `DONE` with `sourceBranch` `release/1.0` |
| E3 | The worktree starts from the tip of `release/1.0`, not from the feature branch the person is on and not from `develop` (the default); the choice is recorded as `publish`. | `baseCommit` equals the `release/1.0` tip; `sourceChoice` is `publish` |
| E4 | The stand-in's commit is on `appmod/fix-<id8>` and the session counts 1 commit; the local-changes flag is false. | `fixCommits` is 1, `localChanges` false |
| E5 | After the session ends `DONE` the engine pushes that branch to `origin`: it exists in the bare repository, its tip is the agent's commit, and its parent is the `release/1.0` tip. | `publishState` `pushed`; `git log` and `rev-parse` in the bare repository |
| E6 | The pull-request command runs once with `{base}` = `release/1.0`, `{head}` = the pushed branch, a title and a description file; the first link it printed is recorded; events say `pushed ...` and `pull request created`. | arguments file, `pullRequestLink`, events |
| E7 | The person's checkout is exactly as before: same branch, same `git status`. | `git status --porcelain -uall` equal before and after |
| E8 | `publish <report> --unsafe-local-changes` warns about the second confirmation; the session starts from a snapshot of `feature/wip` with `x.txt`; it ends `DONE` with `awaiting_confirmation`; nothing is pushed and the command does not run. After `confirmed` the branch is pushed, the command runs, and the description carries a WARNING. | `aSessionThatIncludedLocalChangesIsNotPushedUntilItIsConfirmed` |
| E9 | A branch that does not exist is refused by `publish` with the reason; no message is sent and no session is recorded. | `aBranchThatDoesNotExistIsRefusedAtThePublishAndNothingIsQueued` |

## Result

All five tests pass (E1 to E9; the recorded `Verify --live` run in docs/evidence is after this parameterization; E1 to E7 run once for each sample project: Spring Boot, Node.js and Python, which are files only, no build tool was run). Breaking the code once (skipping the local-changes confirmation) made the second test fail; it was restored and passes.
The full gate, `java scripts/Verify.java --live`, is recorded in [../../docs/evidence/source-branch-verify-live.txt](../../docs/evidence/source-branch-verify-live.txt).

Defects this work found by running it, fixed in the same change:

1. `GitRunner` left a hanging `git-remote-http` child alive after killing git on a timeout; it now ends the whole process tree.
2. An unattended engine could start an askpass or credential dialog (`GIT_ASKPASS`, the machine's credential helper); `GitRunner` now removes those and a test that
   points git at a refusing remote runs without the machine's git configuration.
3. The first version of the Docker helper ran `port` instead of `docker port` and leaked containers when a start failed; it now removes a container it cannot use.
4. **`--unsafe-local-changes` failed in every repository that has the kit.** `appmod init` puts `.appmod/` in `.gitignore`, and the snapshot excluded the agent's
   folder with a pathspec; git refuses a pathspec that names an ignored path (`The following paths are ignored by one of your .gitignore files`, exit 1), and the
   session ended `ERROR` with only git's first line, a line-ending warning, as the reason. The unit tests and the first end-to-end test missed it because their
   repositories had no such `.gitignore`. It was found by a real run of the engine jar (see
   [../../docs/evidence/source-branch-real-engine-run.txt](../../docs/evidence/source-branch-real-engine-run.txt)). Fixed: the snapshot adds everything and then removes
   `.appmod` from the temporary index; a failure's reason now shows git's own error line; regression tests in `SourceBranchTest`, and the end-to-end test's repository
   now ignores `.appmod/` as a real one does.

## Not covered

A hosted git service, `gh`, `glab`, a real coding agent in this flow (the agent's behaviour has its own recorded runs in [../../docs/evidence.md](../../docs/evidence.md)),
a terminal console (the real-run file uses the engine with its console off), and a Linux machine: this was run on Windows 11 with Docker Desktop (Linux engine). The `live` job in CI (Ubuntu) has not run yet, because nothing was pushed.
