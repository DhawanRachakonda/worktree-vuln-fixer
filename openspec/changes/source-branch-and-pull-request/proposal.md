# Proposal

## Why

A fix session today starts from whatever the repository's checkout has at `HEAD` (or the commit a report names), nobody can say which
branch the fixes should be based on, and the result is a local branch that a person has to push and turn into a pull request by hand.
People who fix findings need to choose the source branch when they publish (or rely on a sensible default), may want their uncommitted
local work included, and want the fixes to arrive as a branch on the remote that targets that source branch. All of it has to work for
many repositories on any git host (GitHub, GitLab, Bitbucket Cloud and Server or Data Center, cloud or on-prem), and a person must be told
at `appmod init`, `doctor` and `config` time when git cannot do what the chosen mode needs, not after minutes of agent work.

## What Changes

- `publish` gets `--source-branch <name>` and `--unsafe-local-changes`; both travel as NATS message headers (`source-branch`,
  `unsafe-local-changes`), so a message that is only a path keeps working and CI publishers can set them.
- The worktree's base is chosen in this order: the option, the repository's saved source branch (`.appmod/` setting written by
  `appmod init`), the commit the report names, the repository's default branch (remote default, then `main` or `master`), then `HEAD`.
  **BREAKING:** a saved source branch now wins over the commit named by a report (today the report's commit wins).
- `--unsafe-local-changes` bases the worktree on a snapshot commit of the checkout's tracked and untracked changes, made without
  touching the checkout, and records that fact in the session; the session cannot publish a pull request without a second confirmation.
- After a `succeeded` (or `partially_succeeded`) session the engine, not the agent, pushes the session's branch when the publish mode is
  `push` or `pull-request`, and in `pull-request` mode runs an operator-configured command template (for example `gh pr create` or
  `glab mr create`, with the source branch as target) after the push. The engine ships no host-specific code. The default mode stays
  `none`. **BREAKING for the docs:** "the engine never pushes" becomes "the agent never pushes; the engine pushes only when asked".
- Preflight for the push path: git installed, `origin` present, host reachable with the configured credentials (`git ls-remote`), push
  allowed (`git push --dry-run`, reported as likely, never a real push), source branch exists, the PR command's program is on the PATH.
  It runs in `appmod init` (warnings, with the detected default branch for the person to confirm or change), in `doctor` (per allowed
  repository), shown in `config` (settings only, no network), and, cheaply, at publish before a session is queued.
- Settings are per repository (`.appmod/`), with the mode and the command template overridable engine-wide.
- A second publish for the same report is a duplicate only when its source branch is the same too.
- Non-goal, stated on purpose: two sessions for the same repository still never run at the same time, even on different source branches.
- Docs and prompts that say "never push" or "no pull requests" are corrected, with the agent-side rule kept.

## Capabilities

### New Capabilities
- `source-branch-selection`: the source branch of a session (option, saved setting, default detection), the unsafe-local-changes snapshot,
  and the per-repository setting.
- `branch-publishing`: the engine pushing a finished session's branch and running the optional pull-request command, the three modes,
  what is recorded, and what happens when the push fails.
- `git-preflight`: the checks of git, remote, credentials, branch and PR command in `appmod init`, `doctor`, `config` and at publish.

### Modified Capabilities
- `fix-session-worktrees`: the worktree's base commit follows the new order and may include a snapshot of local changes; "the system
  does not push" becomes "does not push except as `branch-publishing` says".
- `report-publishing`: the console `publish` command takes the two options and sends them as headers.
- `issue-fix-subscriber`: message headers are read and validated; the duplicate rule compares report and source branch; the console's
  `doctor` and `config` cover the git settings.

## Impact

- Engine: `ReportPublisher`, `NatsMessageSource`, `IntakeService`/`IntakeChecker`, `WorktreeService.baseCommit`, `SessionLifecycle`
  (post-session push step), `Doctor`, `ConsoleReports.config`, `SubscriberProperties`, a new `BranchPublisher`, the session record
  (source branch, snapshot flag, push result), the Flyway migration, and the prompts that say "never push, open pull requests".
- Kit: `KitInstaller` (`appmod init` step: detect default branch, run the preflight, write the `.appmod/` setting), the security-fixer
  agent and `appmod-harness` text for the direct (no engine) flow.
- Docs: `README.md`, `engine/docs/{setup,operations,integrations}.md`, `kit/README.md`, `kit/docs/*`, `AGENTS.md`, `docs/evidence.md`.
- No new runtime dependencies. No credentials are stored by this project: git's own credential sources are used.
- Not proven until run for real: any host other than the one used to test; the PR command templates are documented, not shipped.
