# Source branch, push and pull request

This page covers which branch a fix session starts from, how the finished branch can reach the remote, and how a pull request can follow.
The default is unchanged: **nothing is pushed** (publish mode `none`). The agent itself never pushes and never opens a pull request, in
any mode: when you turn publishing on, the **engine** pushes the session's own branch after the session has ended, using git and the
credentials git already has on this machine. No token is stored by this project.

Proof for each statement is in [spec-coverage.md](spec-coverage.md) and [../../docs/evidence.md](../../docs/evidence.md); what was not run is
listed at the end.

## Which branch a session starts from

The worktree starts from the first of these that applies:

1. the branch given when the report was published (`publish <report> --source-branch release/1.2`),
2. the branch saved for the repository (`.appmod/publish.json`, below),
3. the commit the report names in its `commit` column, when it exists in the repository,
4. the repository's default branch: the branch `origin/HEAD` points to, else the branch the remote reports for its `HEAD`, else a local `main`,
   then `master`, else (with no remote) the checked-out branch,
5. `HEAD` of the checkout.

**This changed.** Before, a repository with a feature branch checked out started from that feature branch's `HEAD` when nothing else applied, and
a report's commit beat everything. Now the default branch is used instead of `HEAD`, and a saved or given branch beats the commit a report names
(the session's events say when a report's commit was not used). To get the old behaviour for one publish, use `--unsafe-local-changes` or
`--source-branch <your branch>`.

A given or saved branch that cannot be used (it does not exist locally or as `origin/<name>`, or the name is not valid) ends the session as
`ERROR` with that reason; it never falls back to a lower choice. The engine does not fetch: it uses the local tip it finds, so fetch before you
publish if the remote has moved. A valid branch name is made of letters, digits and `._/-`.

`publish` tells you the branch that will be used (and how it was found) before it publishes, and refuses a branch that does not exist, so a
mistake shows up at the console and not after a queued session fails:

```
publish C:\src\shop\.appmod\security-audit\issues.csv --source-branch release/1.2
publish C:\src\shop\.appmod\security-audit\issues.csv --unsafe-local-changes
```

Both options travel as NATS message headers (`source-branch`, and `unsafe-local-changes` with the value `true`); the body is still only the path,
so a publisher that sends only a path keeps working. A CI job can set the headers with any NATS client that supports headers. The engine's own
`publish` command was run against a real NATS server with both headers (see below); the real `nats pub -H` command-line client (the `natsio/nats-box` image) was also run against the real engine jar with each header ([source-branch-real-engine-run.txt](../../docs/evidence/source-branch-real-engine-run.txt)). A message with an
invalid, repeated or unknown header value, or with both headers, is recorded as `REJECTED` with the reason.

### Saving a branch per repository: `.appmod/publish.json`

```json
{
  "sourceBranch": "develop",
  "mode": "push",
  "pullRequestCommand": "gh pr create --base {base} --head {head} --title {title} --body-file {bodyFile}"
}
```

Every key is optional. The file lives in the repository's own `.appmod/` folder (which is not committed), so several repositories served by
one engine can differ. `appmod init` writes `sourceBranch` only, after you confirm it (see below); `--upgrade` never changes the file. The file
is strict: an unknown key, a value that is not text or a branch name that is not valid makes the whole file a problem, which `doctor` shows and
which makes a session for that repository end as `ERROR` with the same reason.

### Including your uncommitted work: `--unsafe-local-changes`

The worktree starts from a snapshot commit of the current branch's tip plus your tracked and untracked changes (ignored files and the agent's
`.appmod/` folder are left out). The snapshot is made with a temporary index: your branch, files and index are not touched. A clean checkout
starts from the tip and an event says there was nothing to include. The option cannot be combined with `--source-branch`, because local
changes belong to the checked-out branch.

It is called unsafe because the snapshot can hold work in progress, or a secret in an untracked file. So a session started this way is **never
pushed automatically**, whatever the mode: it waits for `publish-branch <session id> --yes` (below).

## Publish modes

| Mode | What the engine does when a session ends `DONE` or `PARTIAL` with at least one commit |
|---|---|
| `none` (default) | Nothing; no connection to the remote. |
| `push` | `git push origin <the session's branch>` from the worktree: no force, no prompt, a time limit, only that branch. |
| `pull-request` | The push, then the command template below, once. |

A failed push or command never changes the session's final state: it is an event, a field (`session` and the read API show `publish_state`,
`pull_request_state`, `pull_request_link`) and, for a push, the command to retry (`publish-branch <id> --yes`). A session that ended
`ERROR`, `NEEDS_INPUT`, `INTERRUPTED` or without commits is never pushed.

Settings: engine-wide `SUBSCRIBER_PUBLISH_MODE` (`none`, `push`, `pull-request`) and `SUBSCRIBER_PULL_REQUEST_COMMAND`
(`subscriber.publish.mode`, `subscriber.publish.pull-request-command`); a repository's `.appmod/publish.json` overrides them. The `config`
command shows both and each fixed repository's saved settings.

## The pull-request command

The engine contains no code for any git host. In `pull-request` mode it runs a command you choose, after the push, in the session's worktree:

- The template is split into arguments first (single or double quotes group words) and the placeholders are filled afterwards, one argument at a
  time, so a value can never become a second argument or shell syntax. **No shell is used.**
- Placeholders: `{base}` (the session's source branch, else the repository's default branch), `{head}` (the pushed branch), `{title}` and
  `{bodyFile}` (a file the engine writes under the session's data folder; it says the branch was produced by an automated session and, when local
  changes were included, that it contains a snapshot of uncommitted work). Any other `{name}` is refused. All four values are made by the engine;
  nothing from a report is ever part of the command.
- The first `http(s)` link the command prints is recorded as the pull request link. A non-zero exit is recorded as a failure; the branch stays
  pushed. An empty template, a program that is not on the PATH, or an unknown placeholder makes the session act as in `push` mode and say why.
  `doctor` shows the program that will run.

Templates to start from. **None of these was run against a real host** (no `gh`, `glab` or hosted repository was available); only a stand-in
program was, which proves the mechanism and not the host:

| Host | Template |
|---|---|
| GitHub (GitHub CLI, already signed in) | `gh pr create --base {base} --head {head} --title {title} --body-file {bodyFile}` |
| GitLab (`glab`, already signed in) | `glab mr create --target-branch {base} --source-branch {head} --title {title} --description-file {bodyFile} --yes` (check the flag names against your `glab` version) |
| Bitbucket Cloud, Server or Data Center | Bitbucket has no official command-line client for this. Write a small program (any language) that takes `base head title bodyFile` as arguments, calls your server's REST API with a token it reads itself, and prints the pull request link; set the template to `<program> {base} {head} {title} {bodyFile}`. |

GitLab push options (`git push -o merge_request.create`) are not offered: the engine pushes first, so a second push would have nothing to apply them to.

## Pushing a session that waits for confirmation, or retrying a push

```
publish-branch <session id>          says what it would push (and warns when the session holds local changes)
publish-branch <session id> --yes    pushes the branch and, in pull-request mode, runs the command
```

It works for a session that ended `DONE` or `PARTIAL` with commits; it refuses an unknown session, one that is not finished, or one without
commits. A session in mode `none` is pushed by this command as in `push` mode.

## Two branches of one repository

A second publish for the same report is a duplicate only when its source branch is the same too; the same report on another branch is a new
session. Two sessions for the same repository still never run at the same time, even on different branches (they share build caches and
locks): the second waits, and `session` and `sessions` say which session it waits behind.

## Finding problems before an agent has worked

| Where | What is checked |
|---|---|
| `appmod init` | git installed, `origin`, the host answers, a push is likely to be accepted, the source branch exists. Shows the detected default branch and how it was found, lists the branches and, in a terminal, lets you confirm or choose one (saved to `.appmod/publish.json`). Without a terminal it saves nothing and says which branch would be used. It installs the kit whatever the result. |
| `doctor` | The same checks for each repository a subject names with a fixed `repository` and worktree isolation, one block each. A repository whose repository is found from the report is checked when you `publish`. |
| `config` | Settings only (mode, command, branch settings); no network. |
| `publish` | Before a session is queued: git works, the saved setting is valid, the source branch exists. |

"Push allowed" is a `git push --dry-run`, reported as *likely*: it sends nothing, and a read-only credential can still fail later. Nothing is
fetched or pushed by any check. Credentials in a remote URL are removed from every message.

The installer keeps its own copy of these checks (it cannot use engine classes); a test compares the two (see [AGENTS.md](../../AGENTS.md)).

## What was and was not run

- Run for real, with git in temporary folders and a local bare repository as the remote: source-branch choice and default-branch detection,
  local-change snapshots, push and refused push, the command with a stub program, the confirmation, the preflight checks and the installer
  step. Branch choice, default-branch detection and the local-change snapshot were also run on small Spring Boot, Node.js and Python sample
  repositories (files only: no Maven, npm or pip was run, the engine only needs git). The two headers went through a real NATS server in a
  Docker container.
- **Not run:** any hosted git service (GitHub, GitLab, Bitbucket, cloud or on-premises), `gh`, `glab`, credentials that need a browser or a
  token prompt. (The `nats pub -H` command line was run, see above.) Hosts differ in credentials, proxies and certificates, so treat the templates above as
  starting points to try on a throwaway repository first.
