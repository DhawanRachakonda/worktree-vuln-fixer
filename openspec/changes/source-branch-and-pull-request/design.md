# Design

## Context

See proposal.md for why. Today the message body is only a report path (`ReportPublisher`, `IntakeService`), the base commit comes from
`WorktreeService.baseCommit` (report commit, else `HEAD`), git runs through `GitRunner` (no shell, no prompt, time limit, repository as
working directory), and the dispatcher never runs two sessions of one repository together. Sessions are stored in SQLite-style records
with Flyway migrations (`V1`, `V2`). The engine and the kit never push. The kit installer (`KitInstaller`, `appmod init`) is Java 25 with no
access to the engine's settings. Windows is the primary platform, so no behaviour may depend on a POSIX shell.

## Goals / Non-Goals

**Goals:**
- One source-branch decision, made in one place, recorded with the session, explained in `session` and in events.
- Pushing and pull requests that work on any git host because only `git` is used by the engine; hosts differ only in a command the
  operator supplies.
- Problems with git found in `init`, `doctor` and at publish, not after the agent has worked.
- The agent keeps no permission to push; the credential never enters an agent session.

**Non-Goals:**
- Host-specific API clients, token storage, fork flows, reviewers or drafts (the operator's command can do those).
- Running two sessions of the same repository at once, also on different source branches: the per-repository rule stays, so branches of
  one repository are processed one after another.
- Fetching from the remote to refresh a branch (the engine uses the local tip it finds).
- Merging, rebasing or resolving conflicts with the source branch.

## Decisions

**1. Options travel as NATS headers, not in the body.** Headers `source-branch` and `unsafe-local-changes`. A path-only message is
unchanged, `nats pub -H` sets them from CI, and nothing about the body format or the path checks moves. Alternative: a JSON body, rejected
because it breaks every existing publisher and the "payload is a single path" rule. Headers are data: validated against
`[A-Za-z0-9._/-]+` (no leading `-`, no `..`, no `//`), and never passed to a shell; they reach git only as an argument after `--`-safe
checks (`rev-parse --verify --end-of-options`).

**2. Resolution lives in a `SourceBranchResolver`.** Order: header, repository setting, report commit, default branch, `HEAD`
(see the spec). It returns `{commit, branch (nullable), choice, notes}`. `WorktreeService.baseCommit` calls it; nothing else decides a base.
A given or saved branch that cannot be used is an error, not a fall-through, so a typo never silently builds on another branch. The saved
branch outranks the report's commit (a deliberate reversal, flagged **BREAKING** in the proposal): someone who set `develop` should not be
overridden by an audit that ran elsewhere; an event records the unused report commit.

**3. Default branch detection uses read-only git.** `symbolic-ref --short refs/remotes/origin/HEAD`, then `ls-remote --symref origin HEAD`
(the same call as the reachability check, with a time limit), then local `main`/`master`, then the checked-out branch. No fetch. The
answer carries a label (`remote default`, `local guess`, `no remote`) shown to the person.

**4. `--unsafe-local-changes` makes a snapshot commit with a temporary index.** Copy the checkout's index to a temporary file, point
`GIT_INDEX_FILE` at it, `git add -A` (respecting ignore rules), `write-tree`, `commit-tree` with the current branch's tip as parent and a
message naming it as uncommitted local work, and delete the temp index. The checkout's index, files and branch are never touched.
`GitRunner` gains an overload that accepts extra environment variables. Alternatives: `git stash create` (misses untracked files),
applying a patch inside the worktree (breaks on binaries and renames). The unreferenced commit is reachable from the worktree's branch.
The flag and the source branch are mutually exclusive: local changes belong to the checked-out branch.

**5. The engine pushes, in `BranchPublisher`, after the final state is known.** `SessionLifecycle` calls it once when a session ends
`succeeded` or `partially_succeeded` with at least one commit. It runs `git push origin <branch>` from the worktree, no force, no prompt,
with a time limit, then (mode `pull-request`) the configured command. The agent's prompts and agent files keep "never push, never open
pull requests", the allow-list is unchanged, and `PromptWriterTest`'s assertion stays true. Why not the agent: it would need a different
command and a token per host, and a planted instruction in a report would become a real push. A failed push never changes the session's
final state; it is an event and a field.

**6. The pull-request step is an operator command template, not an API client.** The template is split into arguments first (quotes
group words), placeholders `{base}`, `{head}`, `{title}`, `{bodyFile}` are replaced afterwards, so a value can never become a second
argument or shell syntax, and it runs without a shell. The engine writes the description to a file under the session's data folder. Docs
list ready-made templates for `gh`, `glab` and GitLab push options (`git push -o merge_request.create ...` can be used as the template
for the push itself); Bitbucket has no official CLI, so its operators supply a script. Alternative: provider adapters over REST (rejected
for this change: per-host auth, TLS and proxy handling for cloud and on-prem versions, and untestable without instances). The adapter
interface can come later without changing the specs here.

**7. Local-change sessions publish only on confirmation.** The snapshot can hold secrets from untracked files, so those sessions are
recorded as `awaiting confirmation` and `publish-branch <id> --yes` pushes (and runs the command). Everything else is automatic when
the mode says so.

**8. Settings: `.appmod/publish.json` in each repository, engine defaults in properties.** Keys `sourceBranch`, `mode`,
`pullRequestCommand`. `.appmod/` is not committed, so the setting is per checkout, which is where the engine looks. Engine properties
`subscriber.publish.mode` (default `none`) and `subscriber.publish.pull-request-command` are the defaults. `appmod init` writes
`sourceBranch` only, after the person confirms it.

**9. Preflight is one `GitPreflight` component, used four times.** It is plain Java over `GitRunner`, returns a list of
`{check, status, detail, consequence}`, and is called by `doctor` (per allowed repository, one block each), by publish (cheap subset:
git works, branch exists, saved setting valid), and by the kit installer. The installer cannot depend on the engine, so there are **two deliberate copies** of the same checks: the engine's `GitPreflight`
(`engine/src/main/java/dev/subscriber/worktree/`) and the kit's single-file copy in `kit/install/`. Decided: two small copies plus a parity
test that runs both against the same fixture repositories and compares check names, statuses and consequence texts, so they cannot drift
apart silently. A shared library is rejected (it needs a build step; the kit is plain `java File.java`). Whoever changes one copy changes
the other in the same commit; AGENTS.md says so. One deliberate difference: in publish mode `none` the engine skips the remote checks and
says so (nothing is pushed), while `appmod init` runs them and only warns, because the person has not chosen a mode yet; the kit copy takes a
flag for that and the parity test compares the engine's answer. `push --dry-run` is reported as
"likely"; no real push probe. `config` shows settings only and never uses the network.

**10. The duplicate rule compares report and source branch.** `IntakeChecker` treats a message as a duplicate only when a queued or
running session has the same report and the same requested source branch (none counts as none). The repository-level serialisation in the
dispatcher is untouched, so two branches of one repository queue behind each other and the console says why a session waits.

**11. Session record.** Migration `V3` adds `source_branch`, `source_choice`, `local_changes` (boolean), `publish_state`
(`none`, `awaiting_confirmation`, `pushed`, `push_failed`), `pull_request_state`, `pull_request_link`. `SessionView` and `session` show them.

## Risks / Trade-offs

- [The saved branch now beats the report's commit, so an audit's exact commit is skipped] → an event says so; `--source-branch` and
  clearing the setting restore the old behaviour; documented as breaking.
- [Local tip may be behind the remote, so the PR can conflict] → the engine does not fetch; the session records the base commit and the
  docs say to fetch before publishing.
- [Snapshot commit publishes work in progress or a secret in an untracked file] → opt-in flag, second confirmation, PR text says so.
- [`git push --dry-run` can pass on a read-only credential] → reported as "likely", never as proven.
- [Operator command is arbitrary code] → it comes from engine or repository settings, never from a message or report; no shell;
  placeholders filled only with engine-made values; the readiness check shows the program it will run.
- [`.appmod/publish.json` is per checkout and untracked] → documented; `appmod init --upgrade` keeps it.
- [Hosts not tested] → docs state which host was used for the real run and mark the rest as untested.
- [Two branches of one repository wait for each other] → deliberate (shared build caches and locks); stated as a non-goal.

## Migration Plan

Default mode `none` and a path-only message keep today's behaviour, except the base choice order: with a saved branch absent and a report
commit present nothing changes; with neither, the base becomes the default branch instead of the checked-out `HEAD`, which differs only
when the checkout is on a feature branch (documented as a change; `--unsafe-local-changes` or `--source-branch` cover that case). Flyway
`V3` is additive. Rollback is reverting the change; nothing pushed remains to undo except branches on the remote, which `branch-publishing`
only creates when the mode is switched on.
